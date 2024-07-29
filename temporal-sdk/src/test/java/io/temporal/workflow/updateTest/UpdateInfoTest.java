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
import io.temporal.client.*;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.UpdateInfo;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.WorkflowWithUpdate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class UpdateInfoTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestUpdateWorkflowImpl.class).build();

  @Test
  public void testUpdateInfo() throws ExecutionException, InterruptedException {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    WorkflowWithUpdate workflow = workflowClient.newWorkflowStub(WorkflowWithUpdate.class, options);
    // To execute workflow client.execute() would do. But we want to start workflow and immediately
    // return.
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);
    WorkflowStub stub = WorkflowStub.fromTyped(workflow);
    UpdateOptions.Builder updateOptionsBuilder =
        UpdateOptions.newBuilder(String.class)
            .setUpdateName("update")
            .setWaitForStage(WorkflowUpdateStage.COMPLETED);

    UpdateHandle handle1 =
        stub.startUpdate(updateOptionsBuilder.setUpdateId("update id 1").build(), 0, "");
    assertEquals("update:update id 1", handle1.getResultAsync().get());

    UpdateHandle handle2 =
        stub.startUpdate(updateOptionsBuilder.setUpdateId("update id 2").build(), 0, "");
    assertEquals("update:update id 2", handle2.getResultAsync().get());

    Assert.assertThrows(
        WorkflowUpdateException.class,
        () -> stub.startUpdate(updateOptionsBuilder.setUpdateId("reject").build(), 0, ""));

    workflow.complete();
    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(execution, Optional.empty())
            .getResult(String.class);
    assertEquals(" update id 1 update id 2", result);
  }

  public static class TestUpdateWorkflowImpl implements WorkflowWithUpdate {
    String state = "initial";
    List<String> updates = new ArrayList<>();
    CompletablePromise<Void> promise = Workflow.newPromise();

    @Override
    public String execute() {
      if (Workflow.getCurrentUpdateInfo().isPresent()) {
        throw ApplicationFailure.newFailure("update info should not be present", "TestFailure");
      }
      promise.get();
      return updates.stream().reduce("", (a, b) -> a + " " + b);
    }

    @Override
    public String getState() {
      return state;
    }

    @Override
    public String update(Integer index, String value) {
      UpdateInfo updateInfo = Workflow.getCurrentUpdateInfo().get();
      Workflow.sleep(Duration.ofMillis(100));
      updates.add(updateInfo.getUpdateId());
      return updateInfo.getUpdateName() + ":" + updateInfo.getUpdateId();
    }

    @Override
    public void updateValidator(Integer index, String value) {
      UpdateInfo updateInfo = Workflow.getCurrentUpdateInfo().get();
      if (updateInfo.getUpdateId() == "reject") {
        throw new RuntimeException("Rejecting update");
      }
    }

    @Override
    public void complete() {
      promise.complete(null);
    }

    @Override
    public void completeValidator() {}
  }
}
