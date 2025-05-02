
package io.temporal.common.metadata

import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowNameTest {

  @Test
  fun `should resolve simple workflow name`() {
    assertEquals("Workflow1", workflowName(Workflow1::class.java))
  }

  @Test
  fun `should resolve workflow name override`() {
    assertEquals("OverriddenWorkflowMethod", workflowName<Workflow2>())
  }

  @Test(expected = IllegalArgumentException::class)
  fun `should fail if not provided with a workflow interface`() {
    workflowName<NotAWorkflow>()
  }

  @WorkflowInterface
  interface Workflow1 {
    @WorkflowMethod
    fun someWorkflowMethod()
  }

  @WorkflowInterface
  interface Workflow2 {
    @WorkflowMethod(name = "OverriddenWorkflowMethod")
    fun someWorkflowMethod(param: String)
  }

  abstract class NotAWorkflow {
    abstract fun aMethod()
  }
}
