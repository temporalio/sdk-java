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

import io.nexusrpc.OperationUnsuccessfulException;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.client.WorkflowFailedException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.*;

public class SyncOperationFailTest extends BaseNexusTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void failSyncOperation() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException exception =
        Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute(""));
    Assert.assertTrue(exception.getCause() instanceof NexusOperationFailure);
    NexusOperationFailure nexusFailure = (NexusOperationFailure) exception.getCause();
    Assert.assertTrue(nexusFailure.getCause() instanceof ApplicationFailure);
    ApplicationFailure applicationFailure = (ApplicationFailure) nexusFailure.getCause();
    Assert.assertEquals("failed to call operation", applicationFailure.getOriginalMessage());
  }

  @Override
  protected SDKTestWorkflowRule getTestWorkflowRule() {
    return testWorkflowRule;
  }

  public static class TestNexus implements TestWorkflow1 {
    @Override
    public String execute(String endpoint) {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .build();

      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setEndpoint(getEndpointName())
              .setOperationOptions(options)
              .build();
      TestNexusServices.TestNexusService1 testNexusService =
          Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class, serviceOptions);
      try {
        testNexusService.operation(Workflow.getInfo().getWorkflowId());
      } catch (NexusOperationFailure nexusFailure) {
        Assert.assertTrue(nexusFailure.getCause() instanceof ApplicationFailure);
        ApplicationFailure applicationFailure = (ApplicationFailure) nexusFailure.getCause();
        Assert.assertEquals("failed to call operation", applicationFailure.getOriginalMessage());
      }

      Promise<String> failPromise =
          Async.function(testNexusService::operation, Workflow.getInfo().getWorkflowId());
      try {
        // Wait for the promise to fail
        failPromise.get();
      } catch (NexusOperationFailure nexusFailure) {
        Assert.assertTrue(nexusFailure.getCause() instanceof ApplicationFailure);
        ApplicationFailure applicationFailure = (ApplicationFailure) nexusFailure.getCause();
        Assert.assertEquals("failed to call operation", applicationFailure.getOriginalMessage());
      }

      NexusOperationHandle handle =
          Workflow.startNexusOperation(
              testNexusService::operation, Workflow.getInfo().getWorkflowId());
      try {
        // Wait for the operation to fail
        handle.getExecution().get();
      } catch (NexusOperationFailure nexusFailure) {
        Assert.assertTrue(nexusFailure.getCause() instanceof ApplicationFailure);
        ApplicationFailure applicationFailure = (ApplicationFailure) nexusFailure.getCause();
        Assert.assertEquals("failed to call operation", applicationFailure.getOriginalMessage());
      }
      try {
        // Since the operation has failed, the result should throw the same exception as well
        handle.getResult().get();
      } catch (NexusOperationFailure nexusFailure) {
        Assert.assertTrue(nexusFailure.getCause() instanceof ApplicationFailure);
        ApplicationFailure applicationFailure = (ApplicationFailure) nexusFailure.getCause();
        Assert.assertEquals("failed to call operation", applicationFailure.getOriginalMessage());
      }
      // Throw an exception to fail the workflow and test that the exception is propagated correctly
      testNexusService.operation(Workflow.getInfo().getWorkflowId());
      // Workflow will not reach this point
      return "fail";
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      // Implemented inline
      return OperationHandler.sync(
          (ctx, details, name) -> {
            throw new OperationUnsuccessfulException("failed to call operation");
          });
    }
  }
}
