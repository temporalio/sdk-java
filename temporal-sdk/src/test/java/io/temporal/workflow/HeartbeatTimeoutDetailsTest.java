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

package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.CompletionClientActivities;
import io.temporal.workflow.shared.TestActivities.CompletionClientActivitiesImpl;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HeartbeatTimeoutDetailsTest {

  private static final Logger log = LoggerFactory.getLogger(HeartbeatTimeoutDetailsTest.class);

  private static final CompletionClientActivitiesImpl activitiesImpl =
      new CompletionClientActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestHeartbeatTimeoutDetails.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @AfterClass
  public static void afterClass() throws Exception {
    activitiesImpl.close();
  }

  @Test
  public void testHeartbeatTimeoutDetails() {
    activitiesImpl.setCompletionClient(
        testWorkflowRule.getWorkflowClient().newActivityCompletionClient());
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("heartbeatValue", result);
  }

  public static class TestHeartbeatTimeoutDetails implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      ActivityOptions options =
          ActivityOptions.newBuilder()
              .setTaskQueue(taskQueue)
              .setHeartbeatTimeout(Duration.ofSeconds(1)) // short heartbeat timeout;
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .build();

      CompletionClientActivities activities =
          Workflow.newActivityStub(CompletionClientActivities.class, options);
      try {
        // false for second argument means to heartbeat once to set details and then stop.
        activities.activityWithDelay(5000, false);
      } catch (ActivityFailure e) {
        TimeoutFailure te = (TimeoutFailure) e.getCause();
        log.info("TestHeartbeatTimeoutDetails expected timeout", e);
        assertEquals(TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE, te.getTimeoutType());
        assertTrue(te.getCause() instanceof TimeoutFailure);
        assertEquals(
            TimeoutType.TIMEOUT_TYPE_HEARTBEAT, ((TimeoutFailure) te.getCause()).getTimeoutType());
        return (te.getLastHeartbeatDetails().get(String.class));
      }
      throw new RuntimeException("unreachable");
    }
  }
}
