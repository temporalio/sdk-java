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

import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.common.converter.KotlinObjectMapperFactory
import io.temporal.testing.internal.SDKTestWorkflowRule
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import java.time.Duration

class WorkflowClientExtTest {

  @Rule
  @JvmField
  val testWorkflowRule = SDKTestWorkflowRule.newBuilder()
    .setWorkflowTypes(TestWorkflowImpl::class.java)
    .setWorkflowClientOptions(
      WorkflowClientOptions {
        setDataConverter(DefaultDataConverter(JacksonJsonPayloadConverter(KotlinObjectMapperFactory.new())))
      }
    )
    .build()

  @Test
  fun `signalWithStart extension should work the same as the original method`() {
    val workflowClient = testWorkflowRule.workflowClient

    val typedStub = workflowClient.newWorkflowStub<TestWorkflow> {
      setWorkflowId("1")
      setTaskQueue(testWorkflowRule.taskQueue)
    }
    workflowClient.signalWithStart {
      add { typedStub.start(Duration.ofSeconds(3)) }
      add { typedStub.collectString("v1") }
    }

    testWorkflowRule.testEnvironment.sleep(Duration.ofSeconds(1))

    val typedStub2 = workflowClient.newWorkflowStub<TestWorkflow> {
      setWorkflowId("1")
      setTaskQueue(testWorkflowRule.taskQueue)
    }
    workflowClient.signalWithStart {
      add { typedStub2.start(Duration.ofSeconds(5)) }
      add { typedStub2.collectString("v2") }
    }

    val untypedStub = WorkflowStub.fromTyped(typedStub)

    Assert.assertEquals(listOf("v1", "v2"), untypedStub.getResult<List<String>>())
  }

  @WorkflowInterface
  interface TestWorkflow {
    @WorkflowMethod
    fun start(waitDuration: Duration): List<String>

    @SignalMethod
    fun collectString(arg: String)
  }

  class TestWorkflowImpl : TestWorkflow {
    private val strings = mutableListOf<String>()

    override fun start(waitDuration: Duration): List<String> {
      Workflow.sleep(waitDuration)
      return strings
    }

    override fun collectString(arg: String) {
      strings += arg
    }
  }
}
