package co.topl.daml.driver

import com.daml.api.util.TimeProvider
import com.daml.ledger.configuration.LedgerId
import com.daml.ledger.offset.Offset
import com.daml.ledger.participant.state.kvutils.Raw
import com.daml.ledger.participant.state.kvutils.api.{CommitMetadata, LedgerReader, LedgerWriter}
import com.daml.ledger.resources.ResourceOwner
import com.daml.lf.data.Ref.ParticipantId
import com.daml.lf.engine.Engine
import com.daml.metrics.Metrics
import com.daml.platform.akkastreams.dispatcher.Dispatcher
import com.daml.telemetry.TelemetryContext

import scala.concurrent.ExecutionContext

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

  override def currentHealth() = ???

  override def events(startExclusive: Option[Offset]) = ???

  override def commit(correlationId: String, envelope: Raw.Envelope, metadata: CommitMetadata)(implicit
    telemetryContext:                TelemetryContext
  ) = ???

}

object BifrostReaderWriter {
  def newDispatcher(): ResourceOwner[Dispatcher[Index]] =
    ResourceOwner.forCloseable(
      () =>
        Dispatcher(
          "Bifrost-key-value-participant-state",
          zeroIndex = StartIndex,
          headAtInitialization = StartIndex
        )
    )
}
