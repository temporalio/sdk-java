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

import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.common.converter.KotlinObjectMapperFactory
import io.temporal.internal.async.FunctionWrappingUtil
import io.temporal.internal.sync.AsyncInternal
import io.temporal.testing.internal.SDKTestWorkflowRule
import org.junit.Assert
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class KotlinAsyncLambdaTest {

  companion object {
    private val success = AtomicBoolean(false)
  }

  @Rule
  @JvmField
  var testWorkflowRule: SDKTestWorkflowRule = SDKTestWorkflowRule.newBuilder()
    .setWorkflowTypes(LambdaWorkflowImpl::class.java)
    .setWorkflowClientOptions(
      WorkflowClientOptions.newBuilder()
        .setDataConverter(DefaultDataConverter(JacksonJsonPayloadConverter(KotlinObjectMapperFactory.new())))
        .build()
    )
    .build()

  @WorkflowInterface
  interface Workflow {
    @WorkflowMethod
    fun execute()
  }

  class LambdaWorkflowImpl : Workflow {
    override fun execute() {
      val lambda = { success.set(true) }
      Assert.assertFalse(
        "This is just a regular lambda function, it's shouldn't be recognized as a method reference to a" +
          " Temporal async stub",
        AsyncInternal.isAsync(lambda)
      )

      Assert.assertFalse(
        "This is just a regular lambda function, it's shouldn't be recognized as a method reference to a" +
          " Temporal async stub",
        AsyncInternal.isAsync(FunctionWrappingUtil.temporalJavaFunctionalWrapper(lambda))
      )

      Async.procedure(lambda).get()
    }
  }

  @Test
  fun asyncLambdaTest() {
    val client = testWorkflowRule.workflowClient
    val options = WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.taskQueue).build()
    val workflowStub = client.newWorkflowStub(Workflow::class.java, options)
    workflowStub.execute()
    assertTrue(success.get())
  }
}
