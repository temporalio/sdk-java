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

import static org.junit.Assert.*;

import io.grpc.*;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testUtils.CountingSlotSupplier;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.tuning.*;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

public class EagerWorkflowTaskDispatchTest {
  private static final StartCallInterceptor START_CALL_INTERCEPTOR = new StartCallInterceptor();
  private final CountingSlotSupplier<WorkflowSlotInfo> workflowTaskSlotSupplier =
      new CountingSlotSupplier<>(100);
  private final CountingSlotSupplier<ActivitySlotInfo> activityTaskSlotSupplier =
      new CountingSlotSupplier<>(100);
  private final CountingSlotSupplier<LocalActivitySlotInfo> localActivitySlotSupplier =
      new CountingSlotSupplier<>(100);
  private final CountingSlotSupplier<NexusSlotInfo> nexusSlotSupplier =
      new CountingSlotSupplier<>(100);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowServiceStubsOptions(
              WorkflowServiceStubsOptions.newBuilder()
                  .setGrpcClientInterceptors(Collections.singletonList(START_CALL_INTERCEPTOR))
                  .build())
          .setWorkflowTypes(EagerWorkflowTaskWorkflowImpl.class)
          // stop built-in worker factory so it's not in our way
          .setDoNotStart(true)
          .build();

  private final ArrayList<WorkerFactory> workerFactories = new ArrayList<>();

  @After
  public void tearDown() throws Exception {
    this.workerFactories.forEach(WorkerFactory::shutdownNow);
    this.workerFactories.forEach(wf -> wf.awaitTermination(10, TimeUnit.SECONDS));
    this.workerFactories.clear();
    START_CALL_INTERCEPTOR.clear();
    assertEquals(
        workflowTaskSlotSupplier.reservedCount.get(), workflowTaskSlotSupplier.releasedCount.get());
    assertEquals(
        activityTaskSlotSupplier.reservedCount.get(), activityTaskSlotSupplier.releasedCount.get());
    assertEquals(
        localActivitySlotSupplier.reservedCount.get(),
        localActivitySlotSupplier.releasedCount.get());
  }

  private WorkerFactory setupWorkerFactory(
      String workerIdentity, boolean registerWorkflows, boolean start) {
    WorkflowClient workflowClient =
        WorkflowClient.newInstance(
            testWorkflowRule.getWorkflowServiceStubs(),
            testWorkflowRule.getWorkflowClient().getOptions().toBuilder()
                .setIdentity(workerIdentity)
                .build());
    WorkerFactory workerFactory = WorkerFactory.newInstance(workflowClient);
    workerFactories.add(workerFactory);

    Worker worker =
        workerFactory.newWorker(
            testWorkflowRule.getTaskQueue(),
            WorkerOptions.newBuilder()
                .setWorkerTuner(
                    new CompositeTuner(
                        workflowTaskSlotSupplier,
                        activityTaskSlotSupplier,
                        localActivitySlotSupplier,
                        nexusSlotSupplier))
                .build());
    if (registerWorkflows) {
      worker.registerWorkflowImplementationTypes(EagerWorkflowTaskWorkflowImpl.class);
    }
    if (start) {
      workerFactory.start();
    }
    return workerFactory;
  }

  @Test
  public void workflowIsEagerlyDispatchedOnTheWorkerRegisteredWithTheCorrespondentClient() {
    WorkerFactory workerFactory1 = setupWorkerFactory("worker1", true, true);
    WorkerFactory workerFactory2 = setupWorkerFactory("worker2", true, true);

    TestWorkflows.NoArgsWorkflow workflowStub1 =
        workerFactory1
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.NoArgsWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setDisableEagerExecution(false)
                    .build());
    workflowStub1.execute();
    assertTrue(START_CALL_INTERCEPTOR.wasLastStartEager);
    TestWorkflows.NoArgsWorkflow workflowStub2 =
        workerFactory2
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.NoArgsWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setDisableEagerExecution(false)
                    .build());
    workflowStub2.execute();
    assertTrue(START_CALL_INTERCEPTOR.wasLastStartEager);

    HistoryEvent workflowTaskStartedEvent1 =
        testWorkflowRule.getHistoryEvent(
            WorkflowStub.fromTyped(workflowStub1).getExecution().getWorkflowId(),
            EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED);
    String wftWorkerIdentity1 =
        workflowTaskStartedEvent1.getWorkflowTaskStartedEventAttributes().getIdentity();
    assertTrue(
        "WFTs from the first workflow stub should be executed by the first worker, actual identity: "
            + wftWorkerIdentity1,
        wftWorkerIdentity1.contains("worker1"));
    assertFalse(
        "WFTs from the first workflow stub should be executed by the first worker only, actual identity: "
            + wftWorkerIdentity1,
        wftWorkerIdentity1.contains("worker2"));

    HistoryEvent workflowTaskStartedEvent2 =
        testWorkflowRule.getHistoryEvent(
            WorkflowStub.fromTyped(workflowStub2).getExecution().getWorkflowId(),
            EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED);
    String wftWorkerIdentity2 =
        workflowTaskStartedEvent2.getWorkflowTaskStartedEventAttributes().getIdentity();
    assertTrue(
        "WFTs from the second workflow stub should be executed by the second worker, actual identity: "
            + wftWorkerIdentity2,
        wftWorkerIdentity2.contains("worker2"));
    assertFalse(
        "WFTs from the second workflow stub should be executed by the second worker only, actual identity: "
            + wftWorkerIdentity2,
        wftWorkerIdentity2.contains("worker1"));
  }

  @Test
  public void testNoEagerWorkflowTaskIfWorkerHasNoWorkflowsRegistered() {
    WorkerFactory workerFactory1 = setupWorkerFactory("worker1", false, true);
    setupWorkerFactory("worker2", true, true);

    TestWorkflows.NoArgsWorkflow workflowStub =
        workerFactory1
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.NoArgsWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setDisableEagerExecution(false)
                    .build());
    workflowStub.execute();
    assertFalse(
        "Eager dispatch shouldn't be requested for activity-only worker",
        START_CALL_INTERCEPTOR.wasLastStartEager);

    HistoryEvent workflowTaskStartedEvent =
        testWorkflowRule.getHistoryEvent(
            WorkflowStub.fromTyped(workflowStub).getExecution().getWorkflowId(),
            EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED);
    String wftWorkerIdentity =
        workflowTaskStartedEvent.getWorkflowTaskStartedEventAttributes().getIdentity();
    assertTrue(
        "WFTs from the workflow stub should be executed by the second worker, because first worker has to workflows registered",
        wftWorkerIdentity.contains("worker2"));
    assertFalse(
        "WFTs from the workflow stub should be executed by the second worker only, because only it has the workflows registered",
        wftWorkerIdentity.contains("worker1"));
  }

  @Test
  public void testNoEagerWorkflowTaskIfWorkerIsNotStarted() {
    WorkerFactory workerFactory1 = setupWorkerFactory("worker1", true, false);
    setupWorkerFactory("worker2", true, true);

    TestWorkflows.NoArgsWorkflow workflowStub =
        workerFactory1
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.NoArgsWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setDisableEagerExecution(false)
                    .build());
    workflowStub.execute();
    assertFalse(
        "Eager dispatch shouldn't be requested for a not started worker",
        START_CALL_INTERCEPTOR.wasLastStartEager);

    HistoryEvent workflowTaskStartedEvent =
        testWorkflowRule.getHistoryEvent(
            WorkflowStub.fromTyped(workflowStub).getExecution().getWorkflowId(),
            EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED);
    String wftWorkerIdentity =
        workflowTaskStartedEvent.getWorkflowTaskStartedEventAttributes().getIdentity();
    assertTrue(
        "WFTs from the workflow stub should be executed by the second worker, because first worker wasn't started",
        wftWorkerIdentity.contains("worker2"));
    assertFalse(
        "WFTs should be executed by the second worker only, because it's the only started worker",
        wftWorkerIdentity.contains("worker1"));
  }

  @Test
  public void testNoEagerWorkflowTaskIfWorkerIsSuspended() {
    WorkerFactory workerFactory1 = setupWorkerFactory("worker1", true, true);
    setupWorkerFactory("worker2", true, true);
    workerFactory1.getWorker(testWorkflowRule.getTaskQueue()).suspendPolling();

    TestWorkflows.NoArgsWorkflow workflowStub =
        workerFactory1
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.NoArgsWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setDisableEagerExecution(false)
                    .build());
    workflowStub.execute();
    assertFalse(
        "Eager dispatch shouldn't be requested for a suspended worker",
        START_CALL_INTERCEPTOR.wasLastStartEager);

    // we are not checking the event history here, because suspension takes time and the task can
    // still be routed
    // to the first worker. But it's enough to check that we didn't request the eager dispatch from
    // the server.
  }

  @Test
  public void testNoEagerWFTIfDisabledOnWorkflowOptions() {
    WorkerFactory workerFactory = setupWorkerFactory("worker1", true, true);

    TestWorkflows.NoArgsWorkflow workflowStub =
        workerFactory
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.NoArgsWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build());
    workflowStub.execute();
    assertFalse(START_CALL_INTERCEPTOR.wasLastStartEager);

    assertFalse(
        "Eager execution is explicitly disabled, so it shouldn't be requested from the Server",
        START_CALL_INTERCEPTOR.wasLastStartEager());
  }

  public static class EagerWorkflowTaskWorkflowImpl implements TestWorkflows.NoArgsWorkflow {
    @Override
    public void execute() {}
  }

  private static class StartCallInterceptor implements ClientInterceptor {

    private Boolean wasLastStartEager;

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      if (method == WorkflowServiceGrpc.getStartWorkflowExecutionMethod()) {
        return new EagerStartSniffingCall<>(next.newCall(method, callOptions));
      }
      return next.newCall(method, callOptions);
    }

    public Boolean wasLastStartEager() {
      return wasLastStartEager;
    }

    public void clear() {
      wasLastStartEager = null;
    }

    private final class EagerStartSniffingCall<ReqT, RespT>
        extends ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT> {

      EagerStartSniffingCall(ClientCall<ReqT, RespT> call) {
        super(call);
      }

      @Override
      public void sendMessage(ReqT message) {
        StartWorkflowExecutionRequest request = (StartWorkflowExecutionRequest) message;
        wasLastStartEager = request.getRequestEagerExecution();
        super.sendMessage(message);
      }
    }
  }
}
