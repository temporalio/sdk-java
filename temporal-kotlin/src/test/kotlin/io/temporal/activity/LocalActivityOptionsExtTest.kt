
package io.temporal.activity

import io.temporal.common.RetryOptions
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class LocalActivityOptionsExtTest {

  @Test
  fun `LocalActivityOptions DSL should be equivalent to builder`() {
    val dslActivityOptions = LocalActivityOptions {
      setStartToCloseTimeout(Duration.ofMinutes(1))
      setScheduleToCloseTimeout(Duration.ofMinutes(5))
      setRetryOptions {
        setInitialInterval(Duration.ofMillis(10))
        setMaximumInterval(Duration.ofMillis(500))
        setBackoffCoefficient(1.5)
        setMaximumAttempts(10)
      }
    }

    val builderActivityOptions = LocalActivityOptions.newBuilder()
      .setStartToCloseTimeout(Duration.ofMinutes(1))
      .setScheduleToCloseTimeout(Duration.ofMinutes(5))
      .setRetryOptions(
        RetryOptions.newBuilder()
          .setInitialInterval(Duration.ofMillis(10))
          .setMaximumInterval(Duration.ofMillis(500))
          .setBackoffCoefficient(1.5)
          .setMaximumAttempts(10)
          .build()
      )
      .build()

    assertEquals(builderActivityOptions, dslActivityOptions)
  }

  @Test
  fun `LocalActivityOptions copy() DSL should merge override options`() {
    val sourceOptions = LocalActivityOptions {
      setStartToCloseTimeout(Duration.ofMinutes(1))
      setScheduleToCloseTimeout(Duration.ofHours(1))
      setRetryOptions {
        setInitialInterval(Duration.ofMillis(100))
        setMaximumInterval(Duration.ofSeconds(1))
        setBackoffCoefficient(1.5)
      }
    }

    val overriddenOptions = sourceOptions.copy {
      setStartToCloseTimeout(Duration.ofSeconds(30))
      setDoNotIncludeArgumentsIntoMarker(true)
    }

    val expectedOptions = LocalActivityOptions {
      setStartToCloseTimeout(Duration.ofSeconds(30))
      setScheduleToCloseTimeout(Duration.ofHours(1))
      setDoNotIncludeArgumentsIntoMarker(true)
      setRetryOptions {
        setInitialInterval(Duration.ofMillis(100))
        setMaximumInterval(Duration.ofSeconds(1))
        setBackoffCoefficient(1.5)
      }
    }

    assertEquals(expectedOptions, overriddenOptions)
  }
}
