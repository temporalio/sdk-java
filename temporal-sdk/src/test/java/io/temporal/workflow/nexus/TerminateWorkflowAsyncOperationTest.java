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

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.failure.TerminatedFailure;
import io.temporal.nexus.WorkflowClientOperationHandlers;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class TerminateWorkflowAsyncOperationTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class, AsyncWorkflowOperationTest.TestOperationWorkflow.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void terminateAsyncOperation() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    WorkflowFailedException exception =
        Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute(""));
    Assert.assertTrue(exception.getCause() instanceof NexusOperationFailure);
    NexusOperationFailure nexusFailure = (NexusOperationFailure) exception.getCause();
    // TODO(https://github.com/temporalio/sdk-java/issues/2358): Test server needs to be fixed to
    // return the correct type
    Assert.assertTrue(
        nexusFailure.getCause() instanceof ApplicationFailure
            || nexusFailure.getCause() instanceof TerminatedFailure);
    if (nexusFailure.getCause() instanceof ApplicationFailure) {
      Assert.assertEquals(
          "operation terminated",
          ((ApplicationFailure) nexusFailure.getCause()).getOriginalMessage());
    } else {
      Assert.assertEquals(
          "operation terminated",
          ((TerminatedFailure) nexusFailure.getCause()).getOriginalMessage());
    }
  }

  @Service
  public interface TestNexusService {
    @Operation
    String operation(String input);

    @Operation
    String terminate(String workflowId);
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofHours(10))
              .build();
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder().setOperationOptions(options).build();
      TestNexusService serviceStub =
          Workflow.newNexusServiceStub(TestNexusService.class, serviceOptions);
      // Start an async operation
      NexusOperationHandle<String> handle =
          Workflow.startNexusOperation(serviceStub::operation, "block");
      // Wait for the operation to start
      String workflowId = handle.getExecution().get().getOperationId().get();
      // Terminate the operation
      serviceStub.terminate(workflowId);
      // Try to get the result, expect this to throw
      handle.getResult().get();
      return "Should not get here";
    }
  }

  @ServiceImpl(service = TestNexusService.class)
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

    @OperationImpl
    public OperationHandler<String, String> terminate() {
      return WorkflowClientOperationHandlers.sync(
          (context, details, client, workflowId) -> {
            client.newUntypedWorkflowStub(workflowId).terminate("terminate for test");
            return "terminated";
          });
    }
  }
}
