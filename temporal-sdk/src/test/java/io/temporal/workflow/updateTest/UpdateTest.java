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

import io.temporal.activity.*;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.*;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import io.temporal.workflow.shared.TestWorkflows.WorkflowWithUpdate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateTest {

  private static final Logger log = LoggerFactory.getLogger(UpdateTest.class);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(WorkerOptions.newBuilder().build())
          .setWorkflowTypes(TestUpdateWorkflowImpl.class, TestWaitingUpdate.class)
          .setActivityImplementations(new ActivityImpl())
          .build();

  @Test
  public void testUpdate() {
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

    SDKTestWorkflowRule.waitForOKQuery(workflow);
    assertEquals("initial", workflow.getState());

    assertEquals(workflowId, execution.getWorkflowId());

    assertEquals("Execute-Hello Update", workflow.update(0, "Hello Update"));
    assertThrows(WorkflowUpdateException.class, () -> workflow.update(-2, "Bad update"));

    testWorkflowRule.waitForTheEndOfWFT(execution.getWorkflowId());
    testWorkflowRule.invalidateWorkflowCache();

    // send an update that will fail in the update handler
    assertThrows(WorkflowUpdateException.class, () -> workflow.complete());
    assertThrows(WorkflowUpdateException.class, () -> workflow.update(1, ""));

    assertEquals("Execute-Hello Update 2", workflow.update(0, "Hello Update 2"));
    assertThrows(WorkflowUpdateException.class, () -> workflow.update(0, "Bad update"));

    workflow.complete();

    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(execution, Optional.empty())
            .getResult(String.class);
    assertEquals("Execute-Hello Update Execute-Hello Update 2", result);
  }

  @Test
  public void testUpdateUntyped() throws ExecutionException, InterruptedException {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    String workflowType = TestWorkflows.WorkflowWithUpdate.class.getSimpleName();
    WorkflowStub workflowStub =
        workflowClient.newUntypedWorkflowStub(
            workflowType,
            SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()));

    workflowStub.start();

    SDKTestWorkflowRule.waitForOKQuery(workflowStub);
    assertEquals("initial", workflowStub.query("getState", String.class));

    // send an update through the sync path
    assertEquals("Execute-Hello", workflowStub.update("update", String.class, 0, "Hello"));
    // send an update through the async path
    UpdateHandle<String> updateRef = workflowStub.startUpdate("update", String.class, 0, "World");
    assertEquals("Execute-World", updateRef.getResultAsync().get());
    // send a bad update that will be rejected through the sync path
    assertThrows(
        WorkflowUpdateException.class,
        () -> workflowStub.update("update", String.class, 0, "Bad Update"));

    // send an update request to a bad name
    assertThrows(
        WorkflowUpdateException.class,
        () -> workflowStub.update("bad_update_name", String.class, 0, "Bad Update"));

    // send a bad update that will be rejected through the sync path
    assertThrows(
        WorkflowUpdateException.class,
        () -> workflowStub.startUpdate("update", String.class, 0, "Bad Update"));

    workflowStub.update("complete", void.class);

    assertEquals("Execute-Hello Execute-World", workflowStub.getResult(String.class));
  }

  @Test
  public void testUpdateHandleNotReturnedUntilCompleteWhenAsked()
      throws ExecutionException, InterruptedException {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    String workflowType = TestWorkflows.WorkflowWithUpdateAndSignal.class.getSimpleName();
    WorkflowStub workflowStub =
        workflowClient.newUntypedWorkflowStub(
            workflowType,
            SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()));

    workflowStub.start();

    SDKTestWorkflowRule.waitForOKQuery(workflowStub);
    assertEquals("initial", workflowStub.query("getState", String.class));

    // Start the update but verify it does not return the handle until the update is complete
    AtomicBoolean updateCompletedLast = new AtomicBoolean(false);
    Future<?> asyncUpdate =
        Executors.newSingleThreadExecutor()
            .submit(
                () -> {
                  UpdateHandle<String> handle =
                      workflowStub.startUpdate(
                          UpdateOptions.newBuilder(String.class).setUpdateName("update").build(),
                          "Enchi");
                  updateCompletedLast.set(true);
                  try {
                    assertEquals("Enchi", handle.getResultAsync().get());
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                });

    workflowStub.signal("signal", "whatever");
    updateCompletedLast.set(false);

    asyncUpdate.get();
    assertTrue(updateCompletedLast.get());
    workflowStub.update("complete", void.class);
    workflowStub.getResult(List.class);
  }

  public static class TestUpdateWorkflowImpl implements WorkflowWithUpdate {
    String state = "initial";
    List<String> updates = new ArrayList<>();
    CompletablePromise<Void> promise = Workflow.newPromise();
    private final TestActivities.TestActivity1 activity =
        Workflow.newActivityStub(
            TestActivities.TestActivity1.class,
            ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofHours(1)).build());

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
      if (value.isEmpty()) {
        // test returning an exception from the update handler
        throw ApplicationFailure.newFailure("Value cannot be an empty string", "NonRetryable");
      }
      String result = activity.execute(value);
      updates.add(result);
      return result;
    }

    @Override
    public void updateValidator(Integer index, String value) {
      if (index < 0) {
        throw new IllegalArgumentException("Validation failed");
      }
      if (updates.size() >= 2) {
        throw new IllegalStateException("Received more then 2 update requests");
      }
    }

    @Override
    public void complete() {
      promise.complete(null);
    }

    @Override
    public void completeValidator() {
      if (updates.size() < 2) {
        throw new RuntimeException("Workflow not ready to complete");
      }
    }
  }

  @ActivityInterface
  public interface GreetingActivities {
    @ActivityMethod
    String hello(String input);
  }

  public static class ActivityImpl implements TestActivities.TestActivity1 {
    @Override
    public String execute(String input) {
      return Activity.getExecutionContext().getInfo().getActivityType() + "-" + input;
    }
  }

  public static class TestWaitingUpdate implements TestWorkflows.WorkflowWithUpdateAndSignal {
    String state = "initial";
    List<String> updates = new ArrayList<>();
    CompletablePromise<Void> signalled = Workflow.newPromise();
    CompletablePromise<Void> promise = Workflow.newPromise();
    private final TestActivities.TestActivity1 activity =
        Workflow.newActivityStub(
            TestActivities.TestActivity1.class,
            ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofHours(1)).build());

    @Override
    public List<String> execute() {
      promise.get();
      return updates;
    }

    @Override
    public String getState() {
      return state;
    }

    @Override
    public void signal(String value) {
      signalled.complete(null);
    }

    @Override
    public String update(String value) {
      Workflow.await(() -> signalled.isCompleted());
      return value;
    }

    @Override
    public void validator(String value) {}

    @Override
    public void complete() {
      promise.complete(null);
    }
  }
}
