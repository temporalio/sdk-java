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

package io.temporal.workflow.signalTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.QueryRejectCondition;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.*;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.QueryableWorkflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SignalTest {

  private static final Logger log = LoggerFactory.getLogger(SignalTest.class);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestSignalWorkflowImpl.class)
          .setTestTimeoutSeconds(15)
          .build();

  @Test
  public void testSignal() {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
            .toBuilder()
            .setWorkflowId(workflowId)
            .build();
    QueryableWorkflow client = workflowClient.newWorkflowStub(QueryableWorkflow.class, options);
    // To execute workflow client.execute() would do. But we want to start workflow and immediately
    // return.
    WorkflowExecution execution = WorkflowClient.start(client::execute);

    testWorkflowRule.sleep(Duration.ofSeconds(1));
    assertEquals(workflowId, execution.getWorkflowId());
    // Calls query multiple times to check at the end of the method that if it doesn't leak threads
    assertEquals("initial", client.getState());
    testWorkflowRule.sleep(Duration.ofSeconds(1));

    client.mySignal("Hello ");
    testWorkflowRule.sleep(Duration.ofSeconds(1));

    // Test client created using WorkflowExecution
    QueryableWorkflow client2 =
        workflowClient.newWorkflowStub(
            QueryableWorkflow.class, execution.getWorkflowId(), Optional.of(execution.getRunId()));
    assertEquals("Hello ", client2.getState());

    testWorkflowRule.sleep(Duration.ofMillis(500));
    client2.mySignal("World!");
    testWorkflowRule.sleep(Duration.ofMillis(500));
    assertEquals("World!", client2.getState());
    assertEquals(
        "Hello World!",
        workflowClient.newUntypedWorkflowStub(execution, Optional.empty()).getResult(String.class));
    client2.execute();
  }

  @Test
  public void testSignalWithStart() throws Exception {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
            .toBuilder()
            .setWorkflowId(workflowId)
            .build();
    QueryableWorkflow client = workflowClient.newWorkflowStub(QueryableWorkflow.class, options);

    // SignalWithStart starts a workflow and delivers the signal to it.
    BatchRequest batch = workflowClient.newSignalWithStartRequest();
    batch.add(client::mySignal, "Hello ");
    batch.add(client::execute);
    WorkflowExecution execution = workflowClient.signalWithStart(batch);
    testWorkflowRule.sleep(Duration.ofSeconds(1));

    // Test client created using WorkflowExecution
    QueryableWorkflow client2 = workflowClient.newWorkflowStub(QueryableWorkflow.class, options);
    // SignalWithStart delivers the signal to the already running workflow.
    BatchRequest batch2 = workflowClient.newSignalWithStartRequest();
    batch2.add(client2::mySignal, "World!");
    batch2.add(client2::execute);
    WorkflowExecution execution2 = workflowClient.signalWithStart(batch2);
    assertEquals(execution, execution2);

    testWorkflowRule.sleep(Duration.ofMillis(500));
    assertEquals("World!", client2.getState());
    assertEquals(
        "Hello World!",
        workflowClient.newUntypedWorkflowStub(execution, Optional.empty()).getResult(String.class));

    // Check if that it starts closed workflow (AllowDuplicate is default IdReusePolicy)
    QueryableWorkflow client3 = workflowClient.newWorkflowStub(QueryableWorkflow.class, options);
    BatchRequest batch3 = workflowClient.newSignalWithStartRequest();
    batch3.add(client3::mySignal, "Hello ");
    batch3.add(client3::execute);
    WorkflowExecution execution3 = workflowClient.signalWithStart(batch3);
    assertEquals(execution.getWorkflowId(), execution3.getWorkflowId());
    client3.mySignal("World!");
    WorkflowStub untyped = WorkflowStub.fromTyped(client3);
    String result = untyped.getResult(String.class);
    assertEquals("Hello World!", result);

    // Make sure that cannot start if closed and RejectDuplicate policy
    QueryableWorkflow client4 =
        workflowClient.newWorkflowStub(
            QueryableWorkflow.class,
            options
                .toBuilder()
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
    BatchRequest batch4 = workflowClient.newSignalWithStartRequest();
    batch4.add(client4::mySignal, "Hello ");
    batch4.add(client4::execute);
    try {
      workflowClient.signalWithStart(batch4);
      fail("DuplicateWorkflowException expected");
    } catch (WorkflowExecutionAlreadyStarted e) {
      assertEquals(execution3.getRunId(), e.getExecution().getRunId());
    }
  }

  @Ignore("flaky")
  @Test
  public void testSignalUntyped() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    String workflowType = QueryableWorkflow.class.getSimpleName();
    AtomicReference<WorkflowExecution> execution = new AtomicReference<>();
    WorkflowStub workflowStub =
        workflowClient.newUntypedWorkflowStub(
            workflowType,
            SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()));
    // To execute workflow client.execute() would do. But we want to start workflow and immediately
    // return.
    testWorkflowRule.registerDelayedCallback(
        Duration.ofSeconds(1),
        () -> {
          assertEquals("initial", workflowStub.query("getState", String.class));
          workflowStub.signal("testSignal", "Hello ");
          testWorkflowRule.sleep(Duration.ofMillis(500));
          while (!"Hello ".equals(workflowStub.query("getState", String.class))) {}
          assertEquals("Hello ", workflowStub.query("getState", String.class));
          workflowStub.signal("testSignal", "World!");
          while (!"World!".equals(workflowStub.query("getState", String.class))) {}
          assertEquals("World!", workflowStub.query("getState", String.class));
          assertEquals(
              "Hello World!",
              workflowClient
                  .newUntypedWorkflowStub(execution.get(), Optional.of(workflowType))
                  .getResult(String.class));
        });
    execution.set(workflowStub.start());
    assertEquals("Hello World!", workflowStub.getResult(String.class));
    assertEquals("World!", workflowStub.query("getState", String.class));
    WorkflowClient client =
        WorkflowClient.newInstance(
            testWorkflowRule.getTestEnvironment().getWorkflowService(),
            WorkflowClientOptions.newBuilder()
                .setNamespace(SDKTestWorkflowRule.NAMESPACE)
                .setQueryRejectCondition(QueryRejectCondition.QUERY_REJECT_CONDITION_NOT_OPEN)
                .build());
    WorkflowStub workflowStubNotOptionRejectCondition =
        client.newUntypedWorkflowStub(execution.get(), Optional.of(workflowType));
    try {
      workflowStubNotOptionRejectCondition.query("getState", String.class);
      fail("unreachable");
    } catch (WorkflowQueryRejectedException e) {
      assertEquals(
          WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED,
          e.getWorkflowExecutionStatus());
    }
  }

  public static class TestSignalWorkflowImpl implements QueryableWorkflow {
    String state = "initial";
    List<String> signals = new ArrayList<>();
    CompletablePromise<Void> promise = Workflow.newPromise();

    @Override
    public String execute() {
      promise.get();
      return signals.get(0) + signals.get(1);
    }

    @Override
    public String getState() {
      return state;
    }

    @Override
    public void mySignal(String value) {
      log.info("TestSignalWorkflowImpl.mySignal value=" + value);
      state = value;
      signals.add(value);
      if (signals.size() == 2) {
        promise.complete(null);
      }
    }
  }

  public static class TestSignalWithStartWorkflowImpl implements QueryableWorkflow {
    String state = "initial";
    List<String> signals = new ArrayList<>();
    CompletablePromise<Void> promise = Workflow.newPromise();

    @Override
    public String execute() {
      promise.get();
      return signals.get(0) + signals.get(1);
    }

    @Override
    public String getState() {
      return state;
    }

    @Override
    public void mySignal(String value) {
      state = value;
      signals.add(value);
      if (signals.size() == 2) {
        promise.complete(null);
      }
    }
  }
}
