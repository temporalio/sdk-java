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

package io.temporal.workflow

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.activity.setRetryOptions
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.common.converter.KotlinObjectMapperFactory
import io.temporal.testing.internal.SDKTestWorkflowRule
import org.junit.Rule
import org.junit.Test
import java.time.Duration

class KotlinActivityStubExtTest {

  @Rule
  @JvmField
  var testWorkflowRule: SDKTestWorkflowRule = SDKTestWorkflowRule.newBuilder()
    .setWorkflowTypes(
      SyncWorkflowImpl::class.java,
      AsyncWorkflowImpl::class.java
    )
    .setActivityImplementations(ActivityImpl())
    .setWorkflowClientOptions(
      WorkflowClientOptions.newBuilder()
        .setDataConverter(DefaultDataConverter(JacksonJsonPayloadConverter(KotlinObjectMapperFactory.new())))
        .build()
    )
    .build()

  @WorkflowInterface
  interface SyncWorkflow {
    @WorkflowMethod
    fun execute()
  }

  class SyncWorkflowImpl : SyncWorkflow {

    override fun execute() {
      val activity = Workflow.newUntypedActivityStub(
        ActivityOptions {
          setStartToCloseTimeout(Duration.ofSeconds(5))
          setRetryOptions { setMaximumAttempts(1) }
        }
      )

      activity.execute<Unit>("Run", "test-argument")
    }
  }

  @WorkflowInterface
  interface AsyncWorkflow {
    @WorkflowMethod
    fun execute()
  }

  class AsyncWorkflowImpl : AsyncWorkflow {
    override fun execute() {
      val activity = Workflow.newUntypedActivityStub(
        ActivityOptions {
          setStartToCloseTimeout(Duration.ofSeconds(5))
          setRetryOptions { setMaximumAttempts(1) }
        }
      )

      val promise = activity.executeAsync<Unit>("Run", "test-argument")
      promise.get()
    }
  }

  @ActivityInterface
  interface TestActivity {
    fun run(arg: String)
  }

  class ActivityImpl : TestActivity {
    override fun run(arg: String) {
    }
  }

  @Test
  fun `execute on untyped activity stub should spread varargs`() {
    val client = testWorkflowRule.workflowClient
    val options = WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.taskQueue).build()
    val workflowStub = client.newWorkflowStub(SyncWorkflow::class.java, options)
    workflowStub.execute()
  }

  @Test
  fun `executeAsync on untyped activity stub should spread varargs`() {
    val client = testWorkflowRule.workflowClient
    val options = WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.taskQueue).build()
    val workflowStub = client.newWorkflowStub(AsyncWorkflow::class.java, options)
    workflowStub.execute()
  }
}
