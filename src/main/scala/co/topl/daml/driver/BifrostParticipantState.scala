package co.topl.daml.driver

import akka.NotUsed
import akka.stream.scaladsl.Source
import co.topl.daml.driver.BifrostParticipantState.{CommitSubmission, State}
import com.daml.daml_lf_dev.DamlLf
import com.daml.daml_lf_dev.DamlLf.Archive
import com.daml.ledger.participant.state.kvutils.{DamlKvutils, Envelope, KeyValueConsumption, KeyValueSubmission, OffsetBuilder, Pretty}
import com.daml.ledger.participant.state.v1.{Configuration, LedgerInitialConditions, Offset, ParticipantId, Party, ReadService, SubmissionId, SubmissionResult, SubmittedTransaction, SubmitterInfo, TimeModel, TransactionMeta, Update, WriteService}
import com.daml.lf.data.{Ref, Time}
import com.daml.lf.data.Time.Timestamp
import com.daml.lf.engine.Engine
import com.daml.metrics.Metrics
import com.daml.platform.akkastreams.dispatcher.Dispatcher
import com.daml.platform.akkastreams.dispatcher.SubSource.OneAfterAnother
import com.google.protobuf.ByteString

import java.io.Serializable
import java.time.Duration
import java.util.UUID
import java.util.concurrent.{CompletableFuture, CompletionStage}
import scala.concurrent.Future

object BifrostParticipantState {

  /** The complete state of the ledger at a given point in time.
    * This emulates a key-value blockchain with a log of commits and a key-value store.
    * The commit log provides the ordering for the log entries, and its height is used
    * as the [[Offset]].
    * */
  case class State(
                    // Log of commits, which are either [[DamlSubmission]]s or heartbeats.
                    // Replaying the commits constructs the store.
                    commitLog: Vector[Commit],
                    // Current record time of the ledger.
                    recordTime: Timestamp,
                    // Store containing both the [[DamlLogEntry]] and [[DamlStateValue]]s.
                    // The store is mutated by applying [[DamlSubmission]]s. The store can
                    // be reconstructed from scratch by replaying [[State.commits]].
                    store: Map[ByteString, ByteString]
                  )

  object State {
    def empty = State(
      commitLog = Vector.empty[Commit],
      recordTime = Timestamp.Epoch,
      store = Map.empty[ByteString, ByteString]
    )

  }

  sealed trait Commit extends Serializable with Product

  final case class CommitSubmission(
                                     entryId: DamlKvutils.DamlLogEntryId,
                                     envelope: ByteString
                                   ) extends Commit

}

class BifrostParticipantState(
                               partipantId: ParticipantId,
                               metrics: Metrics,
                               engine: Engine)
  extends AutoCloseable
    with ReadService
    with WriteService {

  val ledgerId = "Bifrost-participant-node"
  val genesisIndex = 0

  val keyValueSubmission = new KeyValueSubmission(metrics)


  private val rng = scala.util.Random

  // Namespace prefix for log entries.
  private val NS_LOG_ENTRIES = ByteString.copyFromUtf8("L")

  private val ledgerConfig = Configuration(
    generation = 0L,
    timeModel = TimeModel(
      Duration.ofSeconds(0L),
      Duration.ofSeconds(120L),
      Duration.ofSeconds(120L)
    ).get,
    maxDeduplicationTime = Duration.ofDays(1)
  )

  private val dispatcher: Dispatcher[Int] =
    Dispatcher("bifrost-participant-state", zeroIndex = genesisIndex, headAtInitialization = 0)

  private def allocateEntryId: DamlKvutils.DamlLogEntryId = {
    val nonce: Array[Byte] = Array.ofDim(8)
    rng.nextBytes(nonce)
    DamlKvutils.DamlLogEntryId.newBuilder
      .setEntryId(NS_LOG_ENTRIES.concat(ByteString.copyFrom(nonce)))
      .build
  }

  /** Helper for [[dispatcher]] to fetch [[com.daml.ledger.participant.state.kvutils.DamlKvutils.DamlLogEntry]] from the
    * state and convert it into [[com.daml.ledger.participant.state.v1.Update]].
    */
  private def getUpdate(idx: Int, state: State): List[Update] = {
    assert(idx >= 0 && idx < state.commitLog.size)

    state.commitLog(idx) match {
      case CommitSubmission(entryId, _) =>
        state.store
          .get(entryId.getEntryId)
          .map { blob =>
            val logEntry = Envelope.open(blob) match {
              case Left(err)                                 => sys.error(s"getUpdate: cannot open envelope: $err")
              case Right(Envelope.LogEntryMessage(logEntry)) => logEntry
              case Right(_)                                  => sys.error("getUpdate: Envelope did not contain log entry")
            }
            KeyValueConsumption.logEntryToUpdate(entryId, logEntry)
          }
          .getOrElse(
            sys.error(s"getUpdate: ${Pretty.prettyEntryId(entryId)} not found from store!")
          )
    }
  }

  override def getLedgerInitialConditions(): Source[LedgerInitialConditions, NotUsed] = {
    Source.single(LedgerInitialConditions(ledgerId, ledgerConfig, Time.Timestamp.now()))
  }

  override def stateUpdates(beginAfter: Option[Offset]): Source[(Offset, Update), NotUsed] = {
    dispatcher
      .startingAt(
        beginAfter
          .map(OffsetBuilder.highestIndex(_).toInt)
          .getOrElse(genesisIndex),
        OneAfterAnother[Int, List[Update]](
          (idx: Int) => idx + 1,
          (idx: Int) => Future.successful(getUpdate(idx - 1, State.empty))
        )
      )
      .collect {
        case (off, updates) =>
          val updateOffset: (Offset, Int) => Offset =
            if (updates.size > 1) OffsetBuilder.setMiddleIndex else (offset, _) => offset
          updates.zipWithIndex.map {
            case (update, index) =>
              updateOffset(OffsetBuilder.fromLong(off.toLong), index.toInt) -> update
          }
      }
      .mapConcat(identity)
      .filter {
        case (offset, _) => beginAfter.forall(offset > _)
      }
  }

  override def submitTransaction(
                                  submitterInfo: SubmitterInfo,
                                  transactionMeta: TransactionMeta,
                                  transaction: SubmittedTransaction,
                                  estimatedInterpretationCost: Long) = ???

  override def allocateParty(
                              hint: Option[Party],
                              displayName: Option[String],
                              submissionId: SubmissionId): CompletionStage[SubmissionResult] = {
    val party = hint.getOrElse(generateRandomParty())
    val submission =
      keyValueSubmission.partyToSubmission(submissionId, Some(party), displayName, partipantId)

    CompletableFuture.completedFuture({
      CommitSubmission(
        allocateEntryId,
        Envelope.enclose(
          submission
        )
      )
      SubmissionResult.Acknowledged
    })

  }

  private def generateRandomParty(): Ref.Party =
    Ref.Party.assertFromString(s"party-${UUID.randomUUID().toString.take(8)}")


  override def submitConfiguration(
                                    maxRecordTime: Time.Timestamp,
                                    submissionId: SubmissionId,
                                    config: Configuration): CompletionStage[SubmissionResult] = {
    CompletableFuture.completedFuture({
      val submission = keyValueSubmission
        .configurationToSubmission(maxRecordTime, submissionId, partipantId, config)
      // TODO send configuration to a connected Bifrost node
      CommitSubmission(allocateEntryId, Envelope.enclose(submission))
      SubmissionResult.Acknowledged
    })
  }

  override def currentHealth() = ???

  /** Upload a collection of DAML-LF packages to the ledger. */
  override def uploadPackages(
                               submissionId: SubmissionId,
                               archives: List[Archive],
                               sourceDescription: Option[String]
                             ): CompletionStage[SubmissionResult] =
    CompletableFuture.completedFuture({
      CommitSubmission(
        allocateEntryId,
        Envelope.enclose(
          keyValueSubmission
            .archivesToSubmission(
              submissionId,
              archives,
              sourceDescription.getOrElse(""),
              partipantId
            )
        )
      )
      SubmissionResult.Acknowledged
    })

  override def prune(pruneUpToInclusive: Offset, submissionId: SubmissionId) = ???

  override def close() = {}
}
