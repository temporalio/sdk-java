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
import io.temporal.nexus.Nexus;
import io.temporal.nexus.WorkflowRunOperation;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class AsyncWorkflowOperationTest extends BaseNexusTest {
  private static final String WORKFLOW_ID_PREFIX = "test-prefix";

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
      NexusOperationOptions options = NexusOperationOptions.newBuilder().build();
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
      Assert.assertTrue(
          "Operation token should be present", asyncExec.getOperationToken().isPresent());
      // Result should only be completed if the operation is completed
      Assert.assertFalse("Result should not be completed", asyncOpHandle.getResult().isCompleted());
      Assert.assertTrue(asyncExec.getOperationToken().get().startsWith(WORKFLOW_ID_PREFIX));
      // Unblock the operation
      Workflow.newExternalWorkflowStub(OperationWorkflow.class, asyncExec.getOperationToken().get())
          .unblock();
      // Wait for the operation to complete
      Assert.assertEquals("Hello from operation workflow block", asyncOpHandle.getResult().get());
      // Try to call an asynchronous operation that will fail
      try {
        String ignore = serviceStub.operation("fail");
      } catch (NexusOperationFailure e) {
        Assert.assertEquals("TestNexusService1", e.getService());
        Assert.assertEquals("operation", e.getOperation());
        Assert.assertTrue(e.getOperationToken().startsWith(WORKFLOW_ID_PREFIX));
        Assert.assertTrue(e.getCause() instanceof ApplicationFailure);
        ApplicationFailure applicationFailure = (ApplicationFailure) e.getCause();
        Assert.assertEquals("simulated failure", applicationFailure.getOriginalMessage());
        Assert.assertEquals("SimulatedFailureType", applicationFailure.getType());
        Assert.assertEquals("foo", applicationFailure.getDetails().get(String.class));
        Assert.assertTrue(applicationFailure.getCause() instanceof ApplicationFailure);
        ApplicationFailure cause = (ApplicationFailure) applicationFailure.getCause();
        Assert.assertEquals("simulated cause", cause.getOriginalMessage());
        Assert.assertEquals("SimulatedCause", cause.getType());
      }
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
      } else if (arg.equals("fail")) {
        throw ApplicationFailure.newFailureWithCause(
            "simulated failure",
            "SimulatedFailureType",
            ApplicationFailure.newFailure("simulated cause", "SimulatedCause"),
            "foo");
      } else if (arg.equals("ignore-cancel")) {
        Workflow.newDetachedCancellationScope(
                () -> {
                  Workflow.await(() -> unblocked);
                })
            .run();
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
      return WorkflowRunOperation.fromWorkflowMethod(
          (context, details, input) ->
              Nexus.getOperationContext()
                      .getWorkflowClient()
                      .newWorkflowStub(
                          OperationWorkflow.class,
                          WorkflowOptions.newBuilder()
                              .setWorkflowId(WORKFLOW_ID_PREFIX + details.getRequestId())
                              .build())
                  ::execute);
    }
  }
}
