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

package io.temporal.workflow.shared.nexus;

import io.nexusrpc.OperationUnsuccessfulException;
import io.nexusrpc.handler.*;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.nexus.WorkflowHandle;
import io.temporal.nexus.WorkflowRunNexusOperationHandler;
import io.temporal.workflow.shared.TestWorkflows;

@ServiceImpl(service = TestNexusService.class)
public class TestNexusServiceImpl {
  public TestNexusServiceImpl() {}

  @OperationImpl
  public OperationHandler<String, String> sayHello1() {
    // Implemented inline
    return OperationHandler.sync((ctx, details, name) -> "Hello, " + name + "!");
  }

  @OperationImpl
  public OperationHandler<String, String> runWorkflow() {
    // Implemented via handler
    return WorkflowRunNexusOperationHandler.fromWorkflowMethod(
        (OperationContext ctx, OperationStartDetails o, WorkflowClient c, String input) -> {
          TestWorkflows.TestWorkflow1 workflow =
              c.newWorkflowStub(
                  TestWorkflows.TestWorkflow1.class,
                  WorkflowOptions.newBuilder().setWorkflowId(o.getRequestId()).build());
          return workflow::execute;
        });
  }

  @OperationImpl
  public OperationHandler<Long, Void> sleep() {
    // Implemented via handler
    return WorkflowRunNexusOperationHandler.fromWorkflowHandle(
        (OperationContext ctx, OperationStartDetails o, WorkflowClient c, Long input) -> {
          TestWorkflows.TestWorkflowLongArg workflow =
              c.newWorkflowStub(
                  TestWorkflows.TestWorkflowLongArg.class,
                  WorkflowOptions.newBuilder().setWorkflowId(o.getRequestId()).build());
          return WorkflowHandle.fromWorkflowMethod(workflow::execute, input);
        });
  }

  @OperationImpl
  public OperationHandler<Void, String> returnString() {
    // Implemented via handler
    return WorkflowRunNexusOperationHandler.fromWorkflowHandle(
        (OperationContext ctx, OperationStartDetails o, WorkflowClient c, Void unused) -> {
          TestWorkflows.TestWorkflowReturnString workflow =
              c.newWorkflowStub(
                  TestWorkflows.TestWorkflowReturnString.class,
                  WorkflowOptions.newBuilder().setWorkflowId(o.getRequestId()).build());

          return WorkflowHandle.fromWorkflowMethod(workflow::execute);
        });
  }

  @OperationImpl
  public OperationHandler<String, String> fail() {
    return OperationHandler.sync(
        (ctx, details, name) -> {
          throw new OperationUnsuccessfulException("failed to say hello");
        });
  }
}
