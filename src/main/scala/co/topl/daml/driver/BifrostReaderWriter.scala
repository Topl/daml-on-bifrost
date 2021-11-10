package co.topl.daml.driver

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.api.util.TimeProvider
import com.daml.ledger.api.health.{HealthStatus, Healthy}
import com.daml.ledger.configuration.LedgerId
import com.daml.ledger.offset.Offset
import com.daml.ledger.participant.state.kvutils.Raw
import com.daml.ledger.participant.state.kvutils.api.{CommitMetadata, LedgerReader, LedgerRecord, LedgerWriter}
import com.daml.ledger.participant.state.v2.SubmissionResult
import com.daml.ledger.resources.ResourceOwner
import com.daml.lf.data.Ref.ParticipantId
import com.daml.lf.engine.Engine
import com.daml.metrics.Metrics
import com.daml.platform.akkastreams.dispatcher.Dispatcher
import com.daml.telemetry.TelemetryContext

import scala.concurrent.{ExecutionContext, Future}

class BifrostReaderWriter(
  override val participantId: ParticipantId,
  ledgerId:                   LedgerId,
  timeProvider:               TimeProvider,
  metrics:                    Metrics,
  dispatcher:                 Dispatcher[Index],
  engine:                     Engine
)(implicit executionContext:  ExecutionContext)
    extends LedgerReader
    with LedgerWriter {

  override def ledgerId() = ledgerId

  override def currentHealth(): HealthStatus = Healthy

  override def events(startExclusive: Option[Offset]): Source[LedgerRecord, NotUsed] = Source.empty

  override def commit(correlationId: String, envelope: Raw.Envelope, metadata: CommitMetadata)(implicit
    telemetryContext:                TelemetryContext
  ): Future[SubmissionResult] =
    Future(SubmissionResult.Acknowledged)
  //committer.commit(correlationId, envelope, participantId)

}

object BifrostReaderWriter {

  def newDispatcher(): ResourceOwner[Dispatcher[Index]] =
    ResourceOwner.forCloseable(() =>
      Dispatcher(
        "Bifrost-key-value-participant-state",
        zeroIndex = StartIndex,
        headAtInitialization = StartIndex
      )
    )
}
