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
import io.temporal.client.WorkflowOptions;
import io.temporal.nexus.WorkflowRunNexusOperationHandler;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class AsyncWorkflowOperationTest extends BaseNexusTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class, TestOperationWorkflow.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Override
  protected SDKTestWorkflowRule getTestWorkflowRule() {
    return testWorkflowRule;
  }

  @Test
  public void testWorkflowOperation() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("Hello from operation workflow " + testWorkflowRule.getTaskQueue(), result);
  }

  @Test
  public void testWorkflowOperationReplay() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "testAsyncWorkflowOperationTestHistory.json", AsyncWorkflowOperationTest.TestNexus.class);
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(10))
              .build();
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setEndpoint(getEndpointName())
              .setOperationOptions(options)
              .build();
      // Try to call an asynchronous operation in a blocking way
      TestNexusServices.TestNexusService1 serviceStub =
          Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class, serviceOptions);
      // Try to call an asynchronous operation in a blocking way
      String asyncResult = serviceStub.operation(input);
      // Try to call an asynchronous operation in a non-blocking way
      Promise<String> asyncPromise = Async.function(serviceStub::operation, input);
      Assert.assertEquals(asyncPromise.get(), asyncResult);
      // Try to call an asynchronous operation in a non-blocking way using a handle
      NexusOperationHandle<String> asyncOpHandle =
          Workflow.startNexusOperation(serviceStub::operation, "block");
      NexusOperationExecution asyncExec = asyncOpHandle.getExecution().get();
      // Execution id is present for an asynchronous operations
      Assert.assertTrue("Execution id should be present", asyncExec.getOperationId().isPresent());
      // Result should only be completed if the operation is completed
      Assert.assertFalse("Result should not be completed", asyncOpHandle.getResult().isCompleted());
      // Unblock the operation
      Workflow.newExternalWorkflowStub(OperationWorkflow.class, asyncExec.getOperationId().get())
          .unblock();
      // Wait for the operation to complete
      Assert.assertEquals("Hello from operation workflow block", asyncOpHandle.getResult().get());
      return asyncResult;
    }
  }

  @WorkflowInterface
  public interface OperationWorkflow {
    @WorkflowMethod
    String execute(String arg);

    @SignalMethod
    void unblock();
  }

  public static class TestOperationWorkflow implements OperationWorkflow {
    boolean unblocked = false;

    @Override
    public String execute(String arg) {
      if (arg.equals("block")) {
        Workflow.await(() -> unblocked);
      }
      return "Hello from operation workflow " + arg;
    }

    @Override
    public void unblock() {
      unblocked = true;
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return WorkflowRunNexusOperationHandler.fromWorkflowMethod(
          (context, details, client, input) ->
              client.newWorkflowStub(
                      OperationWorkflow.class,
                      WorkflowOptions.newBuilder().setWorkflowId(details.getRequestId()).build())
                  ::execute);
    }
  }
}
