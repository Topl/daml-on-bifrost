package co.topl.daml.driver

import akka.stream.Materializer
import com.daml.api.util.TimeProvider
import com.daml.ledger.configuration.LedgerId
import com.daml.ledger.participant.state.kvutils.api.KeyValueParticipantState
import com.daml.ledger.participant.state.kvutils.app.ReadWriteService
import com.daml.ledger.resources.{Resource, ResourceContext, ResourceOwner}
import com.daml.lf.data.Ref
import com.daml.lf.data.Ref.ParticipantId
import com.daml.lf.engine.Engine
import com.daml.metrics.Metrics
import com.daml.platform.akkastreams.dispatcher.Dispatcher

import java.util.UUID

object BifrostLedger {

  val DefaultTimeProvider: TimeProvider = TimeProvider.UTC

  class Owner(
    initialLedgerId:       Option[LedgerId],
    participantId:         ParticipantId,
    timeProvider:          TimeProvider = DefaultTimeProvider,
    dispatcher:            Dispatcher[Index],
    metrics:               Metrics,
    engine:                Engine
  )(implicit materializer: Materializer)
      extends ResourceOwner[ReadWriteService] {

    override def acquire()(implicit
      resourceContext: ResourceContext
    ): Resource[ReadWriteService] = {

      val ledgerId =
        initialLedgerId.getOrElse(Ref.LedgerString.assertFromString(UUID.randomUUID.toString))
      val reader = new BifrostReaderWriter(
        participantId,
        ledgerId,
        timeProvider,
        metrics,
        dispatcher,
        engine
      )

      Resource.successful(new KeyValueParticipantState(reader, reader, metrics))
    }
  }
}
