package co.topl.daml.driver

import com.daml.ledger.resources.ResourceContext
import org.scalatest.AsyncTestSuite

trait TestResourceContext {
  self: AsyncTestSuite =>

  implicit protected val resourceContext: ResourceContext = ResourceContext(executionContext)
}
