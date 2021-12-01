package co.topl.daml.driver

import akka.stream.Materializer
import com.codahale.metrics.MetricRegistry
import com.daml.ledger.configuration.LedgerId
import com.daml.ledger.participant.state.kvutils.app.ReadWriteService
import com.daml.ledger.resources.ResourceOwner
import com.daml.lf.data.Ref
import com.daml.lf.data.Ref.ParticipantId
import com.daml.lf.engine.Engine
import com.daml.logging.LoggingContext
import com.daml.logging.LoggingContext.newLoggingContext
import com.daml.metrics.Metrics

trait BifrostParticipantStateBase {

  type BifrostParticipantState = ReadWriteService

  protected def newParticipantState(ledgerId: Option[LedgerId] = None, participantId: Option[ParticipantId] = None)(
    implicit materializer:                    Materializer
  ) =
    newLoggingContext { implicit logCtx =>
      participantStateFactory(
        ledgerId = ledgerId,
        participantId = participantId.getOrElse(Ref.ParticipantId.assertFromString("test-participant")),
        metrics = new Metrics(new MetricRegistry),
        sharedEngine = Engine.StableEngine()
      )
    }

  private def participantStateFactory(
    ledgerId:        Option[LedgerId],
    participantId:   ParticipantId,
    metrics:         Metrics,
    sharedEngine:    Engine
  )(implicit logCtx: LoggingContext, materializer: Materializer): ResourceOwner[BifrostParticipantState] =
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
}
