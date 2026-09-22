package io.temporal.workflow

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class ContinueAsNewOptionsExtTest {

  @Test
  fun `ContinueAsNewOptions DSL should set fields correctly`() {
    val options = ContinueAsNewOptions {
      setTaskQueue("TestQueue")
      setWorkflowRunTimeout(Duration.ofMinutes(10))
    }

    assertEquals("TestQueue", options.taskQueue)
    assertEquals(Duration.ofMinutes(10), options.workflowRunTimeout)
  }

  @Test
  fun `ContinueAsNewOptions copy() DSL should override specified fields and preserve others`() {
    val sourceOptions = ContinueAsNewOptions {
      setTaskQueue("TestQueue")
      setWorkflowRunTimeout(Duration.ofMinutes(10))
    }

    val overriddenOptions = sourceOptions.copy {
      setTaskQueue("NewQueue")
    }

    assertEquals("NewQueue", overriddenOptions.taskQueue)
    assertEquals(Duration.ofMinutes(10), overriddenOptions.workflowRunTimeout)
  }
}
