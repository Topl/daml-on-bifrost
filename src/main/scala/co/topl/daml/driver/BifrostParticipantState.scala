package co.topl.daml.driver

import com.daml.daml_lf_dev.DamlLf
import com.daml.ledger.participant.state.v1.{Configuration, Offset, ParticipantId, Party, ReadService, SubmissionId, SubmittedTransaction, SubmitterInfo, TransactionMeta, WriteService}
import com.daml.lf.data.Time
import com.daml.lf.engine.Engine
import com.daml.metrics.Metrics

class BifrostParticipantState(
                               partipantId: ParticipantId,
                               metrics: Metrics,
                               engine: Engine)
  extends AutoCloseable
    with ReadService
    with WriteService {

  val ledgerId = "Bifrost-participant-node"

  override def getLedgerInitialConditions() = ???

  override def stateUpdates(beginAfter: Option[Offset]) = ???

  override def submitTransaction(
                                  submitterInfo: SubmitterInfo,
                                  transactionMeta: TransactionMeta,
                                  transaction: SubmittedTransaction,
                                  estimatedInterpretationCost: Long) = ???

  override def allocateParty(hint: Option[Party], displayName: Option[String], submissionId: SubmissionId) = ???

  override def submitConfiguration(maxRecordTime: Time.Timestamp, submissionId: SubmissionId, config: Configuration) = ???

  override def currentHealth() = ???

  override def prune(pruneUpToInclusive: Offset, submissionId: SubmissionId) = ???

  override def uploadPackages(submissionId: SubmissionId, archives: List[DamlLf.Archive], sourceDescription: Option[String]) = ???

  override def close() = ???
}
