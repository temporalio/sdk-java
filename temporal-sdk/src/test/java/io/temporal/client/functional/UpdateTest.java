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

import static org.junit.Assert.assertThrows;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.*;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.concurrent.ExecutionException;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class UpdateTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(UpdateTest.QuickWorkflowWithUpdateImpl.class)
          .build();

  @Before
  public void checkExternalService() {
    Assume.assumeTrue(
        "skipping because test server does not support update",
        testWorkflowRule.isUseExternalService());
  }

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

    UpdateReference updateRef = workflowStub.updateAsync("update", Void.class, "some-value");
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

    UpdateReference updateRef = workflowStub.updateAsync("update", Void.class, "some-value");
    assertThrows(ExecutionException.class, () -> updateRef.getResultAsync().get());
  }

  public static class QuickWorkflowWithUpdateImpl implements TestWorkflows.TestUpdatedWorkflow {

    @Override
    public String execute() {
      return "done";
    }

    @Override
    public void update(String arg) {}
  }
}
