package co.topl.daml.mongodb

import com.daml.ledger.participant.state.kvutils.store.DamlLogEntryId
import com.daml.ledger.validator.LogEntryIdAllocator
import com.google.protobuf.ByteString

import java.util.UUID

// This is intended to be used only in testing, and is therefore a trivial implementation.
object RandomLogEntryIdAllocator extends LogEntryIdAllocator {

  override def allocate(): DamlLogEntryId =
    DamlLogEntryId.newBuilder
      .setEntryId(ByteString.copyFromUtf8(UUID.randomUUID().toString))
      .build()
}
