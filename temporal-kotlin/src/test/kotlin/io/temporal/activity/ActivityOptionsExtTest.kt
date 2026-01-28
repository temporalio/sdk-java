
package io.temporal.activity

import io.temporal.common.RetryOptions
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class ActivityOptionsExtTest {

  @Test
  fun `ActivityOptions DSL should be equivalent to builder`() {
    val dslActivityOptions = ActivityOptions {
      setTaskQueue("TestQueue")
      setStartToCloseTimeout(Duration.ofMinutes(1))
      setScheduleToCloseTimeout(Duration.ofHours(1))
      setRetryOptions {
        setInitialInterval(Duration.ofMillis(100))
        setMaximumInterval(Duration.ofSeconds(1))
        setBackoffCoefficient(1.5)
      }
    }

    val builderActivityOptions = ActivityOptions.newBuilder()
      .setTaskQueue("TestQueue")
      .setStartToCloseTimeout(Duration.ofMinutes(1))
      .setScheduleToCloseTimeout(Duration.ofHours(1))
      .setRetryOptions(
        RetryOptions.newBuilder()
          .setInitialInterval(Duration.ofMillis(100))
          .setMaximumInterval(Duration.ofSeconds(1))
          .setBackoffCoefficient(1.5)
          .build()
      )
      .build()

    assertEquals(builderActivityOptions, dslActivityOptions)
  }

  @Test
  fun `ActivityOptions copy() DSL should merge override options`() {
    val sourceOptions = ActivityOptions {
      setTaskQueue("TestQueue")
      setStartToCloseTimeout(Duration.ofMinutes(1))
      setScheduleToCloseTimeout(Duration.ofHours(1))
      setRetryOptions {
        setInitialInterval(Duration.ofMillis(100))
        setMaximumInterval(Duration.ofSeconds(1))
        setBackoffCoefficient(1.5)
      }
    }

    val overriddenOptions = sourceOptions.copy {
      setTaskQueue("NewQueue")
      setHeartbeatTimeout(Duration.ofSeconds(1))
    }

    val expectedOptions = ActivityOptions {
      setTaskQueue("NewQueue")
      setStartToCloseTimeout(Duration.ofMinutes(1))
      setScheduleToCloseTimeout(Duration.ofHours(1))
      setHeartbeatTimeout(Duration.ofSeconds(1))
      setRetryOptions {
        setInitialInterval(Duration.ofMillis(100))
        setMaximumInterval(Duration.ofSeconds(1))
        setBackoffCoefficient(1.5)
      }
    }

    assertEquals(expectedOptions, overriddenOptions)
  }
}
