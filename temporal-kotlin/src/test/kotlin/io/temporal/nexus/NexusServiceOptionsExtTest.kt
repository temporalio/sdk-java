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

package io.temporal.nexus

import io.temporal.workflow.NexusOperationOptions
import io.temporal.workflow.NexusServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class NexusServiceOptionsExtTest {

  @Test
  fun `ServiceOptions DSL should be equivalent to builder`() {
    val dslServiceOptions = NexusServiceOptions {
      setEndpoint("TestEndpoint")
      setOperationOptions(
        NexusOperationOptions {
          setScheduleToCloseTimeout(Duration.ofMinutes(1))
        }
      )
      setOperationMethodOptions(
        mapOf(
          "test" to NexusOperationOptions {
            setScheduleToCloseTimeout(Duration.ofMinutes(2))
          }
        )
      )
    }

    val builderServiceOptions = NexusServiceOptions.newBuilder()
      .setEndpoint("TestEndpoint")
      .setOperationOptions(
        NexusOperationOptions.newBuilder()
          .setScheduleToCloseTimeout(Duration.ofMinutes(1))
          .build()
      )
      .setOperationMethodOptions(
        mapOf(
          "test" to NexusOperationOptions.newBuilder()
            .setScheduleToCloseTimeout(Duration.ofMinutes(2))
            .build()
        )
      )
      .build()

    assertEquals(builderServiceOptions, dslServiceOptions)
  }
}
