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

package io.temporal.client

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowClientOptionsExtTest {

  @Test
  fun `WorkflowClientOptions DSL should be equivalent to builder`() {
    val dslOptions = WorkflowClientOptions {
      setNamespace("test")
      setBinaryChecksum("binary1")
    }

    val builderOptions = WorkflowClientOptions.newBuilder()
      .setNamespace("test")
      .setBinaryChecksum("binary1")
      .build()

    assertEquals(builderOptions, dslOptions)
  }

  @Test
  fun `WorkflowClientOptions copy() DSL should merge override options`() {
    val sourceOptions = WorkflowClientOptions {
      setNamespace("test")
      setBinaryChecksum("binary1")
    }

    val overriddenOptions = sourceOptions.copy {
      setIdentity("id")
      setBinaryChecksum("binary2")
    }

    val expectedOptions = WorkflowClientOptions {
      setNamespace("test")
      setIdentity("id")
      setBinaryChecksum("binary2")
    }

    assertEquals(expectedOptions, overriddenOptions)
  }
}
