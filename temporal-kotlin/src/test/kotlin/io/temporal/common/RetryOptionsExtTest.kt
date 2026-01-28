
package io.temporal.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class RetryOptionsExtTest {

  @Test
  fun `RetryOptions() DSL should be equivalent to builder`() {
    val dslRetryOptions = RetryOptions {
      setInitialInterval(Duration.ofMillis(100))
      setMaximumInterval(Duration.ofSeconds(1))
      setBackoffCoefficient(1.5)
      setMaximumAttempts(5)
    }

    val builderRetryOptions = RetryOptions.newBuilder()
      .setInitialInterval(Duration.ofMillis(100))
      .setMaximumInterval(Duration.ofSeconds(1))
      .setBackoffCoefficient(1.5)
      .setMaximumAttempts(5)
      .build()

    assertEquals(builderRetryOptions, dslRetryOptions)
  }

  @Test
  fun `RetryOptions copy() DSL should merge override options`() {
    val sourceOptions = RetryOptions {
      setInitialInterval(Duration.ofMillis(100))
      setMaximumInterval(Duration.ofSeconds(1))
      setBackoffCoefficient(1.5)
      setMaximumAttempts(5)
    }

    val overriddenOptions = sourceOptions.copy {
      setInitialInterval(Duration.ofMillis(10))
      setMaximumAttempts(10)
      setDoNotRetry("some-error")
    }

    val expectedOptions = RetryOptions {
      setInitialInterval(Duration.ofMillis(10))
      setMaximumInterval(Duration.ofSeconds(1))
      setBackoffCoefficient(1.5)
      setMaximumAttempts(10)
      setDoNotRetry("some-error")
    }

    assertEquals(expectedOptions, overriddenOptions)
  }
}
