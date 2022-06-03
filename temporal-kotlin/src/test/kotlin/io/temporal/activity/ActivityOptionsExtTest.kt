/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
