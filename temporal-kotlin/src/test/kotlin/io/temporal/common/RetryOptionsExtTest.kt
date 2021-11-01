/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

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
