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

package io.temporal.workflow.nexus;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.nexus.WorkflowClientOperationHandlers;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

public class CancelAsyncOperationTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class, AsyncWorkflowOperationTest.TestOperationWorkflow.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void asyncOperationImmediatelyCancelled() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    WorkflowFailedException exception =
        Assert.assertThrows(
            WorkflowFailedException.class, () -> workflowStub.execute("immediately"));
    Assert.assertTrue(exception.getCause() instanceof NexusOperationFailure);
    NexusOperationFailure nexusFailure = (NexusOperationFailure) exception.getCause();
    Assert.assertTrue(nexusFailure.getCause() instanceof CanceledFailure);
    CanceledFailure canceledFailure = (CanceledFailure) nexusFailure.getCause();
    Assert.assertEquals(
        "operation canceled before it was started", canceledFailure.getOriginalMessage());

    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "executeNexusOperation TestNexusService1.operation",
            "startNexusOperation TestNexusService1.operation",
            "cancelNexusOperation TestNexusService1.operation");
  }

  @Test
  public void asyncOperationCancelled() {
    Assume.assumeTrue(
        "Test server does not return correct error", testWorkflowRule.isUseExternalService());

    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    WorkflowFailedException exception =
        Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute(""));
    Assert.assertTrue(exception.getCause() instanceof NexusOperationFailure);
    NexusOperationFailure nexusFailure = (NexusOperationFailure) exception.getCause();
    Assert.assertTrue(nexusFailure.getCause() instanceof CanceledFailure);

    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "executeNexusOperation TestNexusService1.operation",
            "startNexusOperation TestNexusService1.operation",
            "interceptExecuteWorkflow 93557ea7-6433-4a5b-a96b-2c4bddaa0454",
            "registerSignalHandlers unblock",
            "newThread workflow-method",
            "await await",
            "cancelNexusOperation TestNexusService1.operation");
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(10))
              .build();
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder().setOperationOptions(options).build();
      TestNexusServices.TestNexusService1 serviceStub =
          Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class, serviceOptions);
      Workflow.newCancellationScope(
              () -> {
                NexusOperationHandle<String> handle =
                    Workflow.startNexusOperation(serviceStub::operation, "block");
                if (input.isEmpty()) {
                  handle.getExecution().get();
                }
                CancellationScope.current().cancel();
                handle.getResult().get();
              })
          .run();
      return "Should not get here";
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return WorkflowClientOperationHandlers.fromWorkflowMethod(
          (context, details, client, input) ->
              client.newWorkflowStub(
                      AsyncWorkflowOperationTest.OperationWorkflow.class,
                      WorkflowOptions.newBuilder().setWorkflowId(details.getRequestId()).build())
                  ::execute);
    }
  }
}
