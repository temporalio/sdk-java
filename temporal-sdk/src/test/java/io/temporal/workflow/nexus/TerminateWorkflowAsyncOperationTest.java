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
import io.temporal.failure.NexusOperationFailure;
import io.temporal.failure.TerminatedFailure;
import io.temporal.nexus.Nexus;
import io.temporal.nexus.WorkflowHandle;
import io.temporal.nexus.WorkflowRunOperation;
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
    Assert.assertTrue(nexusFailure.getCause() instanceof TerminatedFailure);
    Assert.assertEquals(
        "operation terminated", ((TerminatedFailure) nexusFailure.getCause()).getOriginalMessage());
  }

  public static class StartWorkflow {
    public String workflowId;
    public String input;

    public StartWorkflow() {}

    public StartWorkflow(String workflowId, String input) {
      this.workflowId = workflowId;
      this.input = input;
    }
  }

  @Service
  public interface TestNexusService {
    @Operation
    String operation(StartWorkflow input);

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
      String wfId = Workflow.randomUUID().toString();
      NexusOperationHandle<String> handle =
          Workflow.startNexusOperation(serviceStub::operation, new StartWorkflow(wfId, "block"));
      // Wait for the operation to start
      handle.getExecution().get();
      // Terminate the operation
      serviceStub.terminate(wfId);
      // Try to get the result, expect this to throw
      handle.getResult().get();
      return "Should not get here";
    }
  }

  @ServiceImpl(service = TestNexusService.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<StartWorkflow, String> operation() {
      return WorkflowRunOperation.fromWorkflowHandle(
          (context, details, input) ->
              WorkflowHandle.fromWorkflowMethod(
                  Nexus.getOperationContext()
                          .getWorkflowClient()
                          .newWorkflowStub(
                              AsyncWorkflowOperationTest.OperationWorkflow.class,
                              WorkflowOptions.newBuilder().setWorkflowId(input.workflowId).build())
                      ::execute,
                  input.input));
    }

    @OperationImpl
    public OperationHandler<String, String> terminate() {
      return OperationHandler.sync(
          (context, details, workflowId) -> {
            Nexus.getOperationContext()
                .getWorkflowClient()
                .newUntypedWorkflowStub(workflowId)
                .terminate("terminate for test");
            return "terminated";
          });
    }
  }
}
