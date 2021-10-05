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

package io.temporal.workflow

import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.common.converter.KotlinObjectMapperFactory
import io.temporal.internal.async.FunctionWrappingUtil
import io.temporal.internal.sync.AsyncInternal
import io.temporal.testing.internal.SDKTestWorkflowRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class KotlinAsyncCompanionFunctionTest {

  companion object {
    private val success = AtomicBoolean(false)

    @JvmStatic
    fun setSuccess() {
      success.set(true)
    }
  }

  @Rule
  @JvmField
  var testWorkflowRule: SDKTestWorkflowRule = SDKTestWorkflowRule.newBuilder()
    .setWorkflowTypes(CompanionFunctionReferenceWorkflowImpl::class.java)
    .setWorkflowClientOptions(
      WorkflowClientOptions.newBuilder()
        .setDataConverter(DefaultDataConverter(JacksonJsonPayloadConverter(KotlinObjectMapperFactory.new())))
        .build()
    )
    .build()

  @WorkflowInterface
  interface ParentWorkflow {
    @WorkflowMethod
    fun execute()
  }

  class CompanionFunctionReferenceWorkflowImpl : ParentWorkflow {
    override fun execute() {
      assertFalse(
        "This is a reference to companion object static function," +
          " it's shouldn't be recognized as a method reference to a" +
          " Temporal async stub",
        AsyncInternal.isAsync((KotlinAsyncCompanionFunctionTest)::setSuccess)
      )

      assertFalse(
        "This is a reference to companion object static function," +
          " it's shouldn't be recognized as a method reference to a" +
          " Temporal async stub",
        AsyncInternal.isAsync(
          FunctionWrappingUtil.temporalJavaFunctionalWrapper((KotlinAsyncCompanionFunctionTest)::setSuccess)
        )
      )

      Async.procedure((KotlinAsyncCompanionFunctionTest)::setSuccess).get()
    }
  }

  @Test
  fun asyncCompanionFunctionTest() {
    val client = testWorkflowRule.workflowClient
    val options = WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.taskQueue).build()
    val workflowStub = client.newWorkflowStub(ParentWorkflow::class.java, options)
    workflowStub.execute()
    assertTrue(success.get())
  }
}
