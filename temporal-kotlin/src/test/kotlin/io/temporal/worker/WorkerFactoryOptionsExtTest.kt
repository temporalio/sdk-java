package io.temporal.worker

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkerFactoryOptionsExtTest {

  @Test
  fun `WorkerFactoryOptions DSL should set fields correctly`() {
    val options = WorkerFactoryOptions {
      setWorkflowCacheSize(800)
      setMaxWorkflowThreadCount(400)
    }

    assertEquals(800, options.workflowCacheSize)
    assertEquals(400, options.maxWorkflowThreadCount)
  }

  @Test
  fun `WorkerFactoryOptions copy() DSL should override specified fields and preserve others`() {
    val sourceOptions = WorkerFactoryOptions {
      setWorkflowCacheSize(800)
      setMaxWorkflowThreadCount(400)
    }

    val overriddenOptions = sourceOptions.copy {
      setWorkflowCacheSize(1600)
    }

    assertEquals(1600, overriddenOptions.workflowCacheSize)
    assertEquals(400, overriddenOptions.maxWorkflowThreadCount)
  }
}
