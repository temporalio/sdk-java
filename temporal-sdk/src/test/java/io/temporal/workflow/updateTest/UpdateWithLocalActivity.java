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

package io.temporal.workflow.updateTest;

import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.*;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateWithLocalActivity {

  private static final Logger log = LoggerFactory.getLogger(UpdateTest.class);
  private final TestActivities.TestActivitiesImpl activitiesImpl =
      new TestActivities.TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(WorkerOptions.newBuilder().build())
          .setWorkflowTypes(TestUpdateWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testUpdateWithLocalActivities() {
    Assume.assumeTrue(
        "skipping because test server does not support update",
        testWorkflowRule.isUseExternalService());

    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    // To execute workflow client.execute() would do. But we want to start workflow and immediately
    // return.
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    assertEquals(workflowId, execution.getWorkflowId());

    SDKTestWorkflowRule.waitForOKQuery(workflow);
    assertEquals("initial", workflow.getState());

    assertEquals("Hello Update sleepActivity0 activity", workflow.update(0, "Hello Update"));
    testWorkflowRule.waitForTheEndOfWFT(execution.getWorkflowId());
    testWorkflowRule.invalidateWorkflowCache();
    assertEquals("Hello Update 2 sleepActivity1 activity", workflow.update(1, "Hello Update 2"));

    workflow.complete();

    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(execution, Optional.empty())
            .getResult(String.class);
    assertEquals(
        "Hello Update sleepActivity0 activity Hello Update 2 sleepActivity1 activity", result);
  }

  public static class TestUpdateWorkflowImpl implements TestWorkflows.WorkflowWithUpdate {
    String state = "initial";
    List<String> updates = new ArrayList<>();
    CompletablePromise<Void> promise = Workflow.newPromise();
    TestActivities.VariousTestActivities localActivities =
        Workflow.newLocalActivityStub(
            TestActivities.VariousTestActivities.class, SDKTestOptions.newLocalActivityOptions());

    @Override
    public String execute() {
      promise.get();
      return updates.get(0) + " " + updates.get(1);
    }

    @Override
    public String getState() {
      return state;
    }

    @Override
    public String update(Integer index, String value) {
      String result =
          value
              + " "
              + localActivities.sleepActivity(100, index)
              + " "
              + localActivities.activity();
      updates.add(result);
      return result;
    }

    @Override
    public void complete() {
      promise.complete(null);
    }

    @Override
    public void completeValidator() {}

    @Override
    public void updateValidator(Integer index, String value) {
      if (index < 0 || value == "") {
        throw new IllegalArgumentException("Validation failed");
      }
      if (updates.size() >= 2) {
        throw new RuntimeException("Received more then 2 update requests");
      }
    }
  }
}
