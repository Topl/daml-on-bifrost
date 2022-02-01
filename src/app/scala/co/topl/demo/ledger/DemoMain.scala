package co.topl.demo.ledger

import com.daml.ledger.participant.state.kvutils.app.Config
import com.daml.ledger.resources.ResourceContext
import com.daml.resources.ProgramResource

object DemoMain {

  def main(args: Array[String]): Unit = {
    val resource = for {
      config <- Config.ownerWithoutExtras(RunnerName, args)
      owner  <- Owner(config)
    } yield owner
    new ProgramResource(resource).run(ResourceContext.apply)
  }
}