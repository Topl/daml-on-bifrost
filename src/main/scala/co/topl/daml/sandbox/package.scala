package co.topl.daml

import com.daml.ledger.resources.ResourceOwner
import com.daml.platform.akkastreams.dispatcher.Dispatcher

package object sandbox {
  type Index = Int

  private[sandbox] val StartIndex: Index = 0

  def dispatcherOwner: ResourceOwner[Dispatcher[Index]] =
    Dispatcher.owner(
      name = "in-memory-key-value-participant-state",
      zeroIndex = StartIndex,
      headAtInitialization = StartIndex
    )

  private[sandbox] val RunnerName = "In-Memory Ledger"
}
