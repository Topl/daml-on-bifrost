package co.topl.demo.ledger

import co.topl.demo.ledger.InMemoryState.{ImmutableLog, MutableLog, MutableState}
import com.daml.ledger.offset.Offset
import com.daml.ledger.participant.state.kvutils.Raw
import com.daml.ledger.participant.state.kvutils.api.LedgerRecord

import java.util.concurrent.locks.StampedLock
import scala.collection.mutable
import scala.concurrent.{blocking, ExecutionContext, Future}

class InMemoryState private (log: MutableLog, state: MutableState) {
  private val lockCurrentState = new StampedLock()
  @volatile private var lastLogEntryIndex = 0

  def readLog[A](action: ImmutableLog => A): A =
    action(log) // `log` is mutable, but the interface is immutable

  def newHeadSinceLastWrite(): Int = lastLogEntryIndex

  def write[A](action: (MutableLog, MutableState) => Future[A])(implicit
    executionContext:  ExecutionContext
  ): Future[A] =
    for {
      stamp <- Future {
        blocking {
          lockCurrentState.writeLock()
        }
      }
      result <- action(log, state)
        .andThen { case _ =>
          lastLogEntryIndex = log.size - 1
          lockCurrentState.unlock(stamp)
        }
    } yield result
}

object InMemoryState {
  type ImmutableLog = collection.IndexedSeq[LedgerRecord]
  type ImmutableState = collection.Map[Raw.StateKey, Raw.Envelope]

  type MutableLog = mutable.Buffer[LedgerRecord] with ImmutableLog
  type MutableState = mutable.Map[Raw.StateKey, Raw.Envelope] with ImmutableState

  // The first element will never be read because begin offsets are exclusive.
  private val Beginning =
    LedgerRecord(Offset.beforeBegin, Raw.LogEntryId.empty, Raw.Envelope.empty)

  def empty =
    new InMemoryState(
      log = mutable.ArrayBuffer(Beginning),
      state = mutable.Map.empty
    )
}