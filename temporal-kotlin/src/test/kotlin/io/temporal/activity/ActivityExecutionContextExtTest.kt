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

import io.temporal.client.WorkflowClientOptions
import io.temporal.client.newWorkflowStub
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.common.converter.KotlinObjectMapperFactory
import io.temporal.testing.TestWorkflowRule
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.Duration

class ActivityExecutionContextExtTest {

  @Rule
  @JvmField
  var testWorkflowRule = TestWorkflowRule.newBuilder()
    .setWorkflowTypes(TestWorkflowImpl::class.java)
    .setActivityImplementations(TestActivityForHeartbeatDetails())
    .setWorkflowClientOptions(
      WorkflowClientOptions {
        setDataConverter(DefaultDataConverter(JacksonJsonPayloadConverter(KotlinObjectMapperFactory.new())))
      }
    )
    .build()

  @Test
  fun `getHeartbeatDetailsOrNull should correctly deserialize generic activity heartbeat details`() {
    val workflowStub = testWorkflowRule.workflowClient.newWorkflowStub<TestWorkflow> {
      setTaskQueue(testWorkflowRule.taskQueue)
    }
    workflowStub.run()
  }

  @WorkflowInterface
  interface TestWorkflow {
    @WorkflowMethod
    fun run()
  }

  class TestWorkflowImpl : TestWorkflow {
    override fun run() {
      val activityOptions = ActivityOptions {
        setStartToCloseTimeout(Duration.ofMinutes(1))
        setRetryOptions {
          setMaximumAttempts(2)
        }
      }
      val activity = Workflow.newActivityStub(TestActivity::class.java, activityOptions)
      activity.run()
    }
  }

  @ActivityInterface
  interface TestActivity {
    fun run()
  }

  class TestActivityForHeartbeatDetails : TestActivity {

    override fun run() {
      val context = Activity.getExecutionContext()
      val heartbeatDetails = context.getHeartbeatDetailsOrNull<HeartbeatDetails<List<Long>>>()
      if (context.info.attempt == 1) {
        assertNull(heartbeatDetails)
        context.heartbeat(HEARTBEAT_DETAILS)
        throw RuntimeException()
      } else {
        assertEquals(HEARTBEAT_DETAILS, heartbeatDetails)
      }
    }

    companion object {
      private val HEARTBEAT_DETAILS = HeartbeatDetails<List<Long>>("test", listOf(1, 2))
    }
  }

  private data class HeartbeatDetails<T>(
    val id: String,
    val value: T
  )
}
