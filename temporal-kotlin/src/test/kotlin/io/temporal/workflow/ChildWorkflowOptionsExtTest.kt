package io.temporal.workflow

import io.temporal.common.RetryOptions
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class ChildWorkflowOptionsExtTest {

  @Test
  fun `ChildWorkflowOptions DSL should be equivalent to builder`() {
    val dslOptions = ChildWorkflowOptions {
      setTaskQueue("TestQueue")
      setWorkflowRunTimeout(Duration.ofMinutes(5))
      setRetryOptions {
        setInitialInterval(Duration.ofMillis(100))
        setMaximumAttempts(3)
      }
    }

    val builderOptions = ChildWorkflowOptions.newBuilder()
      .setTaskQueue("TestQueue")
      .setWorkflowRunTimeout(Duration.ofMinutes(5))
      .setRetryOptions(
        RetryOptions.newBuilder()
          .setInitialInterval(Duration.ofMillis(100))
          .setMaximumAttempts(3)
          .build()
      )
      .build()

    assertEquals(builderOptions, dslOptions)
  }

  @Test
  fun `setRetryOptions DSL extension should work on ChildWorkflowOptions builder directly`() {
    val builder = ChildWorkflowOptions.newBuilder().setTaskQueue("TestQueue")
    builder.setRetryOptions {
      setInitialInterval(Duration.ofMillis(50))
      setMaximumAttempts(3)
    }

    val expected = ChildWorkflowOptions.newBuilder()
      .setTaskQueue("TestQueue")
      .setRetryOptions(
        RetryOptions.newBuilder()
          .setInitialInterval(Duration.ofMillis(50))
          .setMaximumAttempts(3)
          .build()
      )
      .build()

    assertEquals(expected, builder.build())
  }

  @Test
  fun `ChildWorkflowOptions copy() DSL should merge override options`() {
    val sourceOptions = ChildWorkflowOptions {
      setTaskQueue("TestQueue")
      setWorkflowRunTimeout(Duration.ofMinutes(5))
    }

    val overriddenOptions = sourceOptions.copy {
      setTaskQueue("NewQueue")
    }

    val expectedOptions = ChildWorkflowOptions {
      setTaskQueue("NewQueue")
      setWorkflowRunTimeout(Duration.ofMinutes(5))
    }

    assertEquals(expectedOptions, overriddenOptions)
  }
}
