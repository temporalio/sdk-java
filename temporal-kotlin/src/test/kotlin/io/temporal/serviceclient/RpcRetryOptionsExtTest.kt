package io.temporal.serviceclient

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class RpcRetryOptionsExtTest {

  @Test
  fun `RpcRetryOptions DSL should be equivalent to builder`() {
    val dslOptions = RpcRetryOptions {
      setInitialInterval(Duration.ofMillis(100))
      setMaximumInterval(Duration.ofSeconds(1))
      setBackoffCoefficient(1.5)
      setMaximumAttempts(5)
    }

    val builderOptions = RpcRetryOptions.newBuilder()
      .setInitialInterval(Duration.ofMillis(100))
      .setMaximumInterval(Duration.ofSeconds(1))
      .setBackoffCoefficient(1.5)
      .setMaximumAttempts(5)
      .build()

    assertEquals(builderOptions, dslOptions)
  }

  @Test
  fun `RpcRetryOptions copy() DSL should merge override options`() {
    val sourceOptions = RpcRetryOptions {
      setInitialInterval(Duration.ofMillis(100))
      setMaximumInterval(Duration.ofSeconds(1))
      setBackoffCoefficient(1.5)
      setMaximumAttempts(5)
    }

    val overriddenOptions = sourceOptions.copy {
      setInitialInterval(Duration.ofMillis(10))
      setMaximumAttempts(10)
    }

    val expectedOptions = RpcRetryOptions {
      setInitialInterval(Duration.ofMillis(10))
      setMaximumInterval(Duration.ofSeconds(1))
      setBackoffCoefficient(1.5)
      setMaximumAttempts(10)
    }

    assertEquals(expectedOptions, overriddenOptions)
  }
}
