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

package io.temporal.common.metadata

import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowNameTest {

  @Test
  fun `should resolve simple workflow name`() {
    assertEquals("Workflow1", workflowName(Workflow1::class.java))
  }

  @Test
  fun `should resolve workflow name override`() {
    assertEquals("OverriddenWorkflowMethod", workflowName<Workflow2>())
  }

  @Test(expected = IllegalArgumentException::class)
  fun `should fail if not provided with a workflow interface`() {
    workflowName<NotAWorkflow>()
  }

  @WorkflowInterface
  interface Workflow1 {
    @WorkflowMethod
    fun someWorkflowMethod()
  }

  @WorkflowInterface
  interface Workflow2 {
    @WorkflowMethod(name = "OverriddenWorkflowMethod")
    fun someWorkflowMethod(param: String)
  }

  abstract class NotAWorkflow {
    abstract fun aMethod()
  }
}
