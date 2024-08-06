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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.*;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.statemachines.UnsupportedContinueAsNewRequest;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.WorkflowWithUpdate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;

public class UpdateContinueAsNewInHandlerTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              WorkflowImplementationOptions.newBuilder()
                  .setFailWorkflowExceptionTypes(UnsupportedContinueAsNewRequest.class)
                  .build(),
              TestUpdateWorkflowImpl.class)
          .build();

  @Test
  public void continueAsNewInUpdateHandler() {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    WorkflowWithUpdate workflow = workflowClient.newWorkflowStub(WorkflowWithUpdate.class, options);

    WorkflowExecution execution = WorkflowClient.start(workflow::execute);
    WorkflowStub stub = WorkflowStub.fromTyped(workflow);
    stub.startUpdate(
        UpdateOptions.newBuilder(String.class)
            .setUpdateName("update")
            .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
            .build());
    WorkflowFailedException e =
        assertThrows(WorkflowFailedException.class, () -> stub.getResult(String.class));
    assertEquals(
        "io.temporal.internal.statemachines.UnsupportedContinueAsNewRequest",
        ((ApplicationFailure) e.getCause()).getType());
  }

  public static class TestUpdateWorkflowImpl implements WorkflowWithUpdate {
    String state = "initial";
    List<String> updates = new ArrayList<>();
    CompletablePromise<Void> promise = Workflow.newPromise();

    @Override
    public String execute() {
      promise.get();
      return "";
    }

    @Override
    public String getState() {
      return state;
    }

    @Override
    public String update(Integer index, String value) {
      // Sleep to make sure the update can be accepted before trying to continueAsNew
      Workflow.sleep(Duration.ofSeconds(1));
      // This should throw UnsupportedContinueAsNewRequest
      Workflow.continueAsNew();
      return "";
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
