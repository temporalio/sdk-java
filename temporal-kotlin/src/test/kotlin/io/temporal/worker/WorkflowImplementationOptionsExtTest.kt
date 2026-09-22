package io.temporal.worker

import io.temporal.activity.ActivityOptions
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class WorkflowImplementationOptionsExtTest {

  @Test
  fun `WorkflowImplementationOptions DSL should be equivalent to builder`() {
    val dslOptions = WorkflowImplementationOptions {
      setDefaultActivityOptions(
        ActivityOptions {
          setStartToCloseTimeout(Duration.ofSeconds(10))
        }
      )
    }

    val builderOptions = WorkflowImplementationOptions.newBuilder()
      .setDefaultActivityOptions(
        ActivityOptions.newBuilder()
          .setStartToCloseTimeout(Duration.ofSeconds(10))
          .build()
      )
      .build()

    assertEquals(builderOptions, dslOptions)
  }

  @Test
  fun `setActivityOptions vararg pairs DSL should be equivalent to map builder`() {
    val activityOptions1 = ActivityOptions { setStartToCloseTimeout(Duration.ofSeconds(5)) }
    val activityOptions2 = ActivityOptions { setStartToCloseTimeout(Duration.ofSeconds(10)) }

    val dslOptions = WorkflowImplementationOptions {
      setActivityOptions(
        "activity1" to activityOptions1,
        "activity2" to activityOptions2
      )
    }

    val builderOptions = WorkflowImplementationOptions.newBuilder()
      .setActivityOptions(mapOf("activity1" to activityOptions1, "activity2" to activityOptions2))
      .build()

    assertEquals(builderOptions, dslOptions)
  }
}
