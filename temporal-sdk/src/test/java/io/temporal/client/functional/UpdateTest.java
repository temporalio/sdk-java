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

package io.temporal.client.functional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.*;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.concurrent.ExecutionException;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

public class UpdateTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              UpdateTest.QuickWorkflowWithUpdateImpl.class,
              UpdateTest.LongWorkflowWithUpdateImpl.class)
          .build();

  @Test
  public void updateNonExistentWorkflow() {

    TestWorkflows.TestUpdatedWorkflow workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.TestUpdatedWorkflow.class, "non-existing-id");
    assertThrows(WorkflowNotFoundException.class, () -> workflow.update("some-value"));
  }

  @Test
  public void updateNonExistentWorkflowUntyped() {
    WorkflowStub workflowStub =
        testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub("non-existing-id");
    assertThrows(
        WorkflowNotFoundException.class,
        () -> workflowStub.update("update", Void.class, "some-value"));

    UpdateHandle updateRef = workflowStub.startUpdate("update", Void.class, "some-value");
    assertThrows(ExecutionException.class, () -> updateRef.getResultAsync().get());
  }

  @Test
  public void updateCompletedWorkflow() {
    TestWorkflows.TestUpdatedWorkflow workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.TestUpdatedWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build());
    workflow.execute();
    assertThrows(WorkflowNotFoundException.class, () -> workflow.update("some-value"));
  }

  @Test
  public void updateCompletedWorkflowUntyped() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    String workflowType = TestWorkflows.TestUpdatedWorkflow.class.getSimpleName();
    WorkflowStub workflowStub =
        workflowClient.newUntypedWorkflowStub(
            workflowType,
            SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()));

    WorkflowExecution execution = workflowStub.start();
    workflowStub.getResult(String.class);

    assertThrows(
        WorkflowNotFoundException.class,
        () -> workflowStub.update("update", Void.class, "some-value"));

    UpdateHandle updateRef = workflowStub.startUpdate("update", Void.class, "some-value");
    assertThrows(ExecutionException.class, () -> updateRef.getResultAsync().get());
  }

  @Test
  public void updateWorkflowDuplicateId() {
    Assume.assumeTrue(
        "skipping for real server because the real server does not handle duplicate update ID correctly",
        !testWorkflowRule.isUseExternalService());

    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    String workflowType = TestWorkflows.WorkflowWithUpdate.class.getSimpleName();
    WorkflowStub workflowStub =
        workflowClient.newUntypedWorkflowStub(
            workflowType,
            SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()));

    WorkflowExecution execution = workflowStub.start();
    SDKTestWorkflowRule.waitForOKQuery(workflowStub);

    assertEquals(
        "some-value",
        workflowStub.update(
            "update",
            "update-id",
            execution.getRunId(),
            String.class,
            String.class,
            0,
            "some-value"));
    testWorkflowRule.waitForTheEndOfWFT(execution.getWorkflowId());
    // Try to send another update request with the same update Id
    assertEquals(
        "some-value",
        workflowStub.update(
            "update", "update-id", "", String.class, String.class, 1, "some-other-value"));

    workflowStub.update("complete", void.class);

    workflowStub.getResult(String.class);
  }

  public static class QuickWorkflowWithUpdateImpl implements TestWorkflows.TestUpdatedWorkflow {

    @Override
    public String execute() {
      return "done";
    }

    @Override
    public void update(String arg) {}
  }

  public static class LongWorkflowWithUpdateImpl implements TestWorkflows.WorkflowWithUpdate {
    CompletablePromise<Void> promise = Workflow.newPromise();

    @Override
    public String execute() {
      promise.get();
      return "complete";
    }

    @Override
    public String getState() {
      return "running";
    }

    @Override
    public String update(Integer index, String value) {
      return value;
    }

    @Override
    public void updateValidator(Integer index, String value) {}

    @Override
    public void complete() {
      promise.complete(null);
    }

    @Override
    public void completeValidator() {}
  }
}
