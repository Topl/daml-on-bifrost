package co.topl.daml.driver

import akka.stream.scaladsl.Sink
import com.codahale.metrics.MetricRegistry
import com.daml.ledger.api.testing.utils.AkkaBeforeAndAfterAll
import com.daml.ledger.configuration.LedgerId
import com.daml.ledger.participant.state.kvutils.app.ReadWriteService
import com.daml.ledger.resources.{ResourceContext, ResourceOwner}
import com.daml.lf.data.Ref
import com.daml.lf.data.Ref.ParticipantId
import com.daml.lf.engine.Engine
import com.daml.logging.LoggingContext
import com.daml.logging.LoggingContext.newLoggingContext
import com.daml.metrics.Metrics
import org.scalatest.{AsyncWordSpecLike, Matchers}

import java.util.UUID

class BifrostParticipantStateSpec extends AsyncWordSpecLike with AkkaBeforeAndAfterAll with Matchers {

  import BifrostParticipantStateSpec._

  implicit protected val resourceContext: ResourceContext = ResourceContext(executionContext)

  val sharedEngine = Engine.StableEngine()

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

  def newLedgerId() = Ref.LedgerString.assertFromString(s"ledger-${UUID.randomUUID()}")

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

    "submit a configuration to the node" in {
      ???
    }

    "return current health" in {
      ???
    }

    "upload packages" in {
      ???
    }

    "allocate party" in {
      ???
    }

    "prune" in {
      ???
    }

    "close" in {
      ???
    }

    "return updates from state" in {
      ???
    }

    "submit a new transaction" in {
      ???
    }
  }
}

object BifrostParticipantStateSpec {

  type BifrostParticipantState = ReadWriteService

  private val participantId = Ref.ParticipantId.assertFromString("test-participant")
}

import org.scalatest.AsyncTestSuite

trait TestResourceContext {
  self: AsyncTestSuite =>

  implicit protected val resourceContext: ResourceContext = ResourceContext(executionContext)
}
