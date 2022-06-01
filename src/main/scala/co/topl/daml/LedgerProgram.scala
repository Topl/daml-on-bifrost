package co.topl.daml

import com.daml.ledger.participant.state.kvutils.app.Config
import com.daml.ledger.resources.ResourceContext
import com.daml.resources.ProgramResource

object LedgerProgram {

  def runInMemory(args: Array[String]): Unit = {
    val resource = for {
      config <- Config.ownerWithoutExtras(inmemory.RunnerName, args)
      owner  <- LedgerResourceOwner.createInMemory(config)
    } yield owner
    new ProgramResource(resource).run(ResourceContext.apply)
  }

  def main(args: Array[String]): Unit = runInMemory(args)
}
