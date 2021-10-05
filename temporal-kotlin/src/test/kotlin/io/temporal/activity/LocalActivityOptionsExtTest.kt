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
}
