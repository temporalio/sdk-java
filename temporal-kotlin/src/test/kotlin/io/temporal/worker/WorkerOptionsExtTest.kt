package io.temporal.worker

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkerOptionsExtTest {

  @Test
  fun `WorkerOptions DSL should be equivalent to builder`() {
    val dslOptions = WorkerOptions {
      setMaxConcurrentActivityExecutionSize(10)
      setMaxConcurrentWorkflowTaskExecutionSize(5)
    }

    val builderOptions = WorkerOptions.newBuilder()
      .setMaxConcurrentActivityExecutionSize(10)
      .setMaxConcurrentWorkflowTaskExecutionSize(5)
      .build()

    assertEquals(builderOptions, dslOptions)
  }

  @Test
  fun `WorkerOptions copy() DSL should merge override options`() {
    val sourceOptions = WorkerOptions {
      setMaxConcurrentActivityExecutionSize(10)
      setMaxConcurrentWorkflowTaskExecutionSize(5)
    }

    val overriddenOptions = sourceOptions.copy {
      setMaxConcurrentActivityExecutionSize(20)
    }

    val expectedOptions = WorkerOptions {
      setMaxConcurrentActivityExecutionSize(20)
      setMaxConcurrentWorkflowTaskExecutionSize(5)
    }

    assertEquals(expectedOptions, overriddenOptions)
  }
}
