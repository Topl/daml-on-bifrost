package co.topl.daml.driver

import akka.stream.scaladsl.Sink
import com.codahale.metrics.MetricRegistry
import com.daml.daml_lf_dev.DamlLf
import com.daml.ledger.api.health.Healthy
import com.daml.ledger.api.testing.utils.AkkaBeforeAndAfterAll
import com.daml.ledger.configuration.{LedgerId, LedgerTimeModel}
import com.daml.ledger.offset.Offset
import com.daml.ledger.participant.state.kvutils.OffsetBuilder.{fromLong => toOffset}
import com.daml.ledger.participant.state.kvutils.app.ReadWriteService
import com.daml.ledger.participant.state.v2.Update.{ConfigurationChanged, PartyAddedToParticipant, PublicPackageUpload}
import com.daml.ledger.participant.state.v2.{SubmissionResult, Update}
import com.daml.ledger.resources.{ResourceContext, ResourceOwner}
import com.daml.lf.archive.DarParser
import com.daml.lf.data.Ref
import com.daml.lf.data.Ref.{ParticipantId, SubmissionId}
import com.daml.lf.data.Time.Timestamp
import com.daml.lf.engine.Engine
import com.daml.logging.LoggingContext
import com.daml.logging.LoggingContext.newLoggingContext
import com.daml.metrics.Metrics
import com.daml.telemetry.{NoOpTelemetryContext, TelemetryContext}
import org.scalatest.Inside.inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpecLike
import org.scalatest.{Assertion, BeforeAndAfterEach}

import java.time.{Clock, Duration}
import java.util.UUID
import java.util.zip.ZipInputStream
import scala.compat.java8.FutureConverters.CompletionStageOps
import scala.concurrent.Future
import scala.concurrent.duration._

class BifrostParticipantStateSpec
    extends AsyncWordSpecLike
    with AkkaBeforeAndAfterAll
    with Matchers
    with BeforeAndAfterEach {

  import BifrostParticipantStateSpec._

  implicit protected val telemetryContext: TelemetryContext = NoOpTelemetryContext
  implicit protected val resourceContext: ResourceContext = ResourceContext(executionContext)

  val sharedEngine = Engine.StableEngine()

  private var testId: String = _

  private var rt: Timestamp = _

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    testId = UUID.randomUUID().toString
    rt = Timestamp.assertFromInstant(Clock.systemUTC().instant())
  }

  private def inTheFuture(duration: FiniteDuration): Timestamp =
    rt.add(Duration.ofNanos(duration.toNanos))

  private def participantState: ResourceOwner[BifrostParticipantState] = newParticipantState()

  private def newParticipantState(ledgerId: Option[LedgerId] = None) = newLoggingContext { implicit logCtx =>
    participantStateFactory(
      ledgerId = ledgerId,
      participantId = participantId,
      new Metrics(new MetricRegistry)
    )
  }

  private def participantStateFactory(
    ledgerId:        Option[LedgerId],
    participantId:   ParticipantId,
    metrics:         Metrics
  )(implicit logCtx: LoggingContext): ResourceOwner[BifrostParticipantState] =
    for {
      dispatcher <- BifrostReaderWriter.newDispatcher()
      participantState <- new BifrostLedger.Owner(
        initialLedgerId = ledgerId,
        participantId = participantId,
        dispatcher = dispatcher,
        metrics = metrics,
        engine = sharedEngine
      )
    } yield participantState

  private def waitForNextUpdate(
    ps:          BifrostParticipantState,
    afterOffset: Option[Offset] = None
  ): Future[(Offset, Update)] =
    ps.stateUpdates(beginAfter = afterOffset)
      .idleTimeout(IdleTimeout)
      .runWith(Sink.head)

  private def matchPackageUpload(
    update:               Update,
    expectedSubmissionId: SubmissionId,
    expectedArchives:     List[DamlLf.Archive]
  ): Assertion =
    inside(update) {
      case PublicPackageUpload(
            actualArchives,
            actualSourceDescription,
            _,
            Some(actualSubmissionId)
          ) =>
        actualArchives.map(_.getHash).toSet should be(expectedArchives.map(_.getHash).toSet)
        actualSourceDescription should be(sourceDescription)
        actualSubmissionId should be(expectedSubmissionId)
    }

  def newLedgerId() = Ref.LedgerString.assertFromString(s"ledger-${UUID.randomUUID()}")

  private def newSubmissionId(): SubmissionId =
    Ref.LedgerString.assertFromString(s"submission-${UUID.randomUUID()}")

  "Participant State" should {
    "return the initial configuration" in {
      val ledgerId = newLedgerId()
      newParticipantState(Some(ledgerId)).use { ps =>
        for {
          conditions <- ps
            .ledgerInitialConditions()
            .runWith(Sink.head)
        } yield conditions.ledgerId should be(ledgerId)
      }
    }

    "submit a configuration to the node" ignore participantState.use { ps =>
      for {
        cond <- ps.ledgerInitialConditions().runWith(Sink.head)

        _ <- ps
          .submitConfiguration(
            maxRecordTime = inTheFuture(10.seconds),
            submissionId = newSubmissionId(),
            config = cond.config.copy(
              generation = cond.config.generation + 1,
              timeModel = LedgerTimeModel(
                Duration.ofSeconds(123),
                Duration.ofSeconds(123),
                Duration.ofSeconds(123)
              ).get
            )
          )
          .toScala
        (offset, update) <- waitForNextUpdate(ps, None)
      } yield inside(update) { case ConfigurationChanged(_, _, _, newConfiguration) =>
        newConfiguration should not be cond.config
      }
    }

    "return current health" in participantState.use { ps =>
      ps.currentHealth() should be(Healthy)
    }

    "upload packages" ignore participantState.use { ps =>
      val submissionId = newSubmissionId()
      for {
        result <- ps.uploadPackages(submissionId, List(archives.head), sourceDescription).toScala
        _ = result should be(SubmissionResult.Acknowledged)
        (offset, update) <- ps
          .stateUpdates(beginAfter = None)
          .idleTimeout(IdleTimeout)
          .runWith(Sink.head)
      } yield {
        offset should be(toOffset(1))
        update.recordTime should be >= rt
        matchPackageUpload(update, submissionId, List(archives.head))
      }
    }

    "allocate party" ignore participantState.use { ps =>
      val partyHint = Ref.Party.assertFromString("Alice")
      val displayName = "Alice Cooper"

      for {
        result <- ps
          .allocateParty(Some(partyHint), Some(displayName), newSubmissionId())
          .toScala
        _ = result should be(SubmissionResult.Acknowledged)
        (offset, update) <- waitForNextUpdate(ps, None)
      } yield {
        offset should be(toOffset(1))
        update.recordTime should be >= rt
        inside(update) { case PartyAddedToParticipant(party, actualDisplayName, actualParticipantId, _, _) =>
          party should be(partyHint)
          actualDisplayName should be(displayName)
          actualParticipantId should be(participantId)
        }
      }
    }
  }
}

object BifrostParticipantStateSpec {

  type BifrostParticipantState = ReadWriteService

  private val participantId = Ref.ParticipantId.assertFromString("test-participant")
  private val sourceDescription = Some("provided by test")
  private val IdleTimeout: FiniteDuration = 5.seconds

  private val testDar = DarParser.readArchive(
    "create-daml-app-0.1.0.dar",
    new ZipInputStream(this.getClass.getClassLoader.getResourceAsStream("create-daml-app-0.1.0.dar"))
  ) match {
    case Left(value)  => throw new Exception(s"Could not read DAR")
    case Right(value) => value
  }

  private val archives = testDar.all

}

import org.scalatest.AsyncTestSuite

trait TestResourceContext {
  self: AsyncTestSuite =>

  implicit protected val resourceContext: ResourceContext = ResourceContext(executionContext)
}
