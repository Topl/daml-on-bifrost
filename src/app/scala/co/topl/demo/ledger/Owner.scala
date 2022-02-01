package co.topl.demo.ledger

import com.daml.ledger.participant.state.kvutils.app.{Config, Runner}
import com.daml.ledger.resources.ResourceOwner

object Owner {

  // Utility if you want to spin this up as a library.
  def apply(config: Config[Unit]): ResourceOwner[Unit] =
    for {
      dispatcher <- dispatcherOwner
      sharedState = InMemoryState.empty
      factory = new InMemoryLedgerFactory(dispatcher, sharedState)
      runner <- new Runner(RunnerName, factory).owner(config)
    } yield runner
}
