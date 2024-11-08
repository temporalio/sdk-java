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
import io.temporal.client.WorkflowOptions;
import io.temporal.nexus.WorkflowClientOperationHandlers;
import io.temporal.nexus.WorkflowHandle;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.NexusOperationOptions;
import io.temporal.workflow.NexusServiceOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowHandleStubTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              TestNexus.class, TestMultiArgWorkflowFunctions.TestMultiArgWorkflowImpl.class)
          .setNexusServiceImplementation(new TestNexusServiceFuncImpl())
          .build();

  @Test
  public void stubTests() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("success", result);
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

      TestNexusServiceProc serviceStub =
          Workflow.newNexusServiceStub(TestNexusServiceProc.class, serviceOptions);
      for (int i = 0; i < 7; i++) {
        serviceStub.operation(i);
      }
      return "success";
    }
  }

  @Service
  public interface TestNexusServiceProc {
    @Operation
    Void operation(Integer input);
  }

  @ServiceImpl(service = TestNexusServiceProc.class)
  public class TestNexusServiceFuncImpl {
    @OperationImpl
    public OperationHandler<Integer, Void> operation() {
      return WorkflowClientOperationHandlers.fromWorkflowHandle(
          (context, details, client, input) -> {
            switch (input) {
              case 0:
                return WorkflowHandle.fromWorkflowStub(
                    client.newUntypedWorkflowStub(
                        "TestNoArgsWorkflowProc",
                        WorkflowOptions.newBuilder().setWorkflowId(details.getRequestId()).build()),
                    Void.class);
              case 1:
                return WorkflowHandle.fromWorkflowStub(
                    client.newUntypedWorkflowStub(
                        "Test1ArgWorkflowProc",
                        WorkflowOptions.newBuilder().setWorkflowId(details.getRequestId()).build()),
                    Void.class,
                    "input");
              case 2:
                return WorkflowHandle.fromWorkflowStub(
                    client.newUntypedWorkflowStub(
                        "Test2ArgWorkflowProc",
                        WorkflowOptions.newBuilder().setWorkflowId(details.getRequestId()).build()),
                    Void.class,
                    "input",
                    2);
              case 3:
                return WorkflowHandle.fromWorkflowStub(
                    client.newUntypedWorkflowStub(
                        "Test3ArgWorkflowProc",
                        WorkflowOptions.newBuilder().setWorkflowId(details.getRequestId()).build()),
                    Void.class,
                    "input",
                    2,
                    3);
              case 4:
                return WorkflowHandle.fromWorkflowStub(
                    client.newUntypedWorkflowStub(
                        "Test4ArgWorkflowProc",
                        WorkflowOptions.newBuilder().setWorkflowId(details.getRequestId()).build()),
                    Void.class,
                    "input",
                    2,
                    3,
                    4);
              case 5:
                return WorkflowHandle.fromWorkflowStub(
                    client.newUntypedWorkflowStub(
                        "Test5ArgWorkflowProc",
                        WorkflowOptions.newBuilder().setWorkflowId(details.getRequestId()).build()),
                    Void.class,
                    "input",
                    2,
                    3,
                    4,
                    5);
              case 6:
                return WorkflowHandle.fromWorkflowStub(
                    client.newUntypedWorkflowStub(
                        "Test6ArgWorkflowProc",
                        WorkflowOptions.newBuilder().setWorkflowId(details.getRequestId()).build()),
                    Void.class,
                    "input",
                    2,
                    3,
                    4,
                    5,
                    6);
              default:
                return null;
            }
          });
    }
  }
}
