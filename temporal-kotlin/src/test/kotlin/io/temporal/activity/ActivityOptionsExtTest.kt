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
}
