package co.topl.daml

import com.daml.ledger.resources.ResourceOwner
import com.daml.platform.akkastreams.dispatcher.Dispatcher

package object inmemory {
  type Index = Int

  val StartIndex: Index = 0

  def dispatcherOwner: ResourceOwner[Dispatcher[Index]] =
    Dispatcher.owner(
      name = "in-memory-key-value-participant-state",
      zeroIndex = StartIndex,
      headAtInitialization = StartIndex
    )

  val RunnerName = "In-Memory Ledger"
}
