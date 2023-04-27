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

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.*;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateWithSignalAndQuery {
  private static final Logger log = LoggerFactory.getLogger(UpdateTest.class);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(WorkerOptions.newBuilder().build())
          .setWorkflowTypes(UpdateWithSignalAndQuery.TestUpdateWithSignalWorkflowImpl.class)
          .setActivityImplementations(new UpdateTest.ActivityImpl())
          .build();

  @Test
  public void testUpdateWithSignal() {
    Assume.assumeTrue(
        "skipping for test server because test server does not support update",
        testWorkflowRule.isUseExternalService());

    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    TestWorkflows.WorkflowWithUpdateAndSignal workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdateAndSignal.class, options);
    // To execute workflow client.execute() would do. But we want to start workflow and immediately
    // return.
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    assertEquals(workflowId, execution.getWorkflowId());

    SDKTestWorkflowRule.waitForOKQuery(workflow);
    assertEquals("initial", workflow.getState());

    assertEquals("update 1", workflow.update("update 1"));
    workflow.signal("signal 1");
    assertEquals("update 2", workflow.update("update 2"));

    testWorkflowRule.waitForTheEndOfWFT(execution.getWorkflowId());
    testWorkflowRule.invalidateWorkflowCache();

    assertEquals("update 3", workflow.update("update 3"));

    testWorkflowRule.waitForTheEndOfWFT(execution.getWorkflowId());
    testWorkflowRule.invalidateWorkflowCache();

    workflow.signal("signal 2");

    workflow.complete();

    assertEquals(
        Arrays.asList("update 1", "signal 1", "update 2", "update 3", "signal 2"),
        workflow.execute());
  }

  public static class TestUpdateWithSignalWorkflowImpl
      implements TestWorkflows.WorkflowWithUpdateAndSignal {
    String state = "initial";
    List<String> updatesAndSignals = new ArrayList<>();
    CompletablePromise<Void> promise = Workflow.newPromise();
    TestActivities.VariousTestActivities localActivities =
        Workflow.newLocalActivityStub(
            TestActivities.VariousTestActivities.class, SDKTestOptions.newLocalActivityOptions());

    @Override
    public List<String> execute() {
      promise.get();
      return updatesAndSignals;
    }

    @Override
    public String getState() {
      return state;
    }

    @Override
    public void signal(String value) {
      updatesAndSignals.add(value);
    }

    @Override
    public String update(String value) {
      updatesAndSignals.add(value);
      return value;
    }

    @Override
    public void complete() {
      promise.complete(null);
    }
  }
}
