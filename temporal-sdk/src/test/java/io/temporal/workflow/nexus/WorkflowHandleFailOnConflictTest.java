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

import io.nexusrpc.handler.HandlerException;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.nexus.Nexus;
import io.temporal.nexus.WorkflowRunOperation;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowHandleFailOnConflictTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class, TestOperationWorkflow.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .setUseExternalService(true)
          .build();

  @Test
  public void testOnConflictFail() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String workflowId = UUID.randomUUID().toString();
    String result = workflowStub.execute(workflowId);
    Assert.assertEquals("Hello from operation workflow " + workflowId, result);
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      TestNexusServices.TestNexusService1 serviceStub =
          Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class);
      // Start asynchronous operations backed by the workflow
      List<NexusOperationHandle<String>> handles = new ArrayList<>();
      for (int i = 0; i < 5; i++) {
        handles.add(Workflow.startNexusOperation(serviceStub::operation, input));
      }
      // Wait for all operations to either start or fail
      int started = 0;
      int failed = 0;
      for (NexusOperationHandle<String> handle : handles) {
        try {
          handle.getExecution().get();
          started++;
        } catch (NexusOperationFailure nof) {
          if (nof.getCause() instanceof HandlerException) {
            HandlerException he = (HandlerException) nof.getCause();
            if (he.getCause() instanceof ApplicationFailure
                && ((ApplicationFailure) he.getCause())
                    .getType()
                    .equals("io.temporal.client.WorkflowExecutionAlreadyStarted")) {
              failed++;
            }
          }
        }
      }
      // We expect one operation to start and four to fail
      Assert.assertEquals(1, started);
      Assert.assertEquals(4, failed);

      // Signal the operation to unblock
      Workflow.newExternalWorkflowStub(OperationWorkflow.class, input).unblock();

      // Wait for all operations to complete
      String result = null;
      started = 0;
      failed = 0;
      for (NexusOperationHandle<String> handle : handles) {
        try {
          result = handle.getResult().get();
          started++;
        } catch (NexusOperationFailure nof) {
          if (nof.getCause() instanceof HandlerException) {
            HandlerException he = (HandlerException) nof.getCause();
            if (he.getCause() instanceof ApplicationFailure
                && ((ApplicationFailure) he.getCause())
                    .getType()
                    .equals("io.temporal.client.WorkflowExecutionAlreadyStarted")) {
              failed++;
            }
          }
        }
      }
      // We expect one operation to complete and four to fail
      Assert.assertEquals(1, started);
      Assert.assertEquals(4, failed);
      return result;
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
      Workflow.await(() -> unblocked);
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
                          AsyncWorkflowOperationTest.OperationWorkflow.class,
                          WorkflowOptions.newBuilder()
                              .setWorkflowId(input)
                              .setWorkflowIdConflictPolicy(
                                  WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                              .build())
                  ::execute);
    }
  }
}
