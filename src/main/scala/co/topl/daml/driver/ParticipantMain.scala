package co.topl.daml.driver

import com.daml.ledger.participant.state.kvutils.app.Runner
import com.daml.ledger.resources.ResourceContext
import com.daml.resources.ProgramResource

object ParticipantMain extends App {

  new ProgramResource(new Runner("Bifrost MongoDB Ledger", MongoLedgerFactory).owner(args))
    .run(ResourceContext.apply)
}
