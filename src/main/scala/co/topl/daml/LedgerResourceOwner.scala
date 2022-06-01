package co.topl.daml

import com.daml.ledger.participant.state.kvutils.app.Config
import com.daml.ledger.resources.ResourceOwner
import co.topl.daml.inmemory.{dispatcherOwner, InMemoryLedgerFactory, InMemoryState, RunnerName}
import com.daml.ledger.participant.state.kvutils.app.Runner

object LedgerResourceOwner {

  def createInMemory(config: Config[Unit]): ResourceOwner[Unit] =
    for {
      dispatcher <- dispatcherOwner
      sharedState = InMemoryState.empty
      factory = new InMemoryLedgerFactory(dispatcher, sharedState)
      runner <- new Runner(RunnerName, factory).owner(config)
    } yield runner
}
