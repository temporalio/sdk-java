/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.testUtils;

import static io.temporal.internal.common.InternalUtils.createNormalTaskList;
import static io.temporal.internal.common.InternalUtils.createStickyTaskList;

import com.google.protobuf.ByteString;
import io.temporal.proto.common.StickyExecutionAttributes;
import io.temporal.proto.common.TaskList;
import io.temporal.proto.common.WorkflowExecution;
import io.temporal.proto.common.WorkflowType;
import io.temporal.proto.workflowservice.PollForDecisionTaskRequest;
import io.temporal.proto.workflowservice.PollForDecisionTaskResponse;
import io.temporal.proto.workflowservice.RespondDecisionTaskCompletedRequest;
import io.temporal.proto.workflowservice.RespondDecisionTaskFailedRequest;
import io.temporal.proto.workflowservice.SignalWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.StartWorkflowExecutionRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.ArrayList;
import java.util.UUID;

public class TestServiceUtils {
  private TestServiceUtils() {}

  public static void startWorkflowExecution(
      String namespace, String tasklistName, String workflowType, WorkflowServiceStubs service)
      throws Exception {
    startWorkflowExecution(namespace, tasklistName, workflowType, 100, 100, service);
  }

  public static void startWorkflowExecution(
      String namespace,
      String tasklistName,
      String workflowType,
      int executionStartToCloseTimeoutSeconds,
      int taskStartToCloseTimeoutSeconds,
      WorkflowServiceStubs service)
      throws Exception {
    StartWorkflowExecutionRequest.Builder request = StartWorkflowExecutionRequest.newBuilder();
    request.setNamespace(namespace);
    request.setWorkflowId(UUID.randomUUID().toString());
    request.setTaskList(createNormalTaskList(tasklistName));
    request.setExecutionStartToCloseTimeoutSeconds(executionStartToCloseTimeoutSeconds);
    request.setTaskStartToCloseTimeoutSeconds(taskStartToCloseTimeoutSeconds);
    request.setWorkflowType(WorkflowType.newBuilder().setName(workflowType));
    service.blockingStub().startWorkflowExecution(request.build());
  }

  public static void respondDecisionTaskCompletedWithSticky(
      ByteString taskToken, String stickyTasklistName, WorkflowServiceStubs service)
      throws Exception {
    respondDecisionTaskCompletedWithSticky(taskToken, stickyTasklistName, 100, service);
  }

  public static void respondDecisionTaskCompletedWithSticky(
      ByteString taskToken,
      String stickyTasklistName,
      int startToCloseTimeout,
      WorkflowServiceStubs service)
      throws Exception {
    RespondDecisionTaskCompletedRequest.Builder request =
        RespondDecisionTaskCompletedRequest.newBuilder();
    StickyExecutionAttributes.Builder attributes = StickyExecutionAttributes.newBuilder();
    attributes.setWorkerTaskList(createStickyTaskList(stickyTasklistName));
    attributes.setScheduleToStartTimeoutSeconds(startToCloseTimeout);
    request.setStickyAttributes(attributes);
    request.setTaskToken(taskToken);
    request.addAllDecisions(new ArrayList<>());
    service.blockingStub().respondDecisionTaskCompleted(request.build());
  }

  public static void respondDecisionTaskFailedWithSticky(
      ByteString taskToken, WorkflowServiceStubs service) throws Exception {
    RespondDecisionTaskFailedRequest request =
        RespondDecisionTaskFailedRequest.newBuilder().setTaskToken(taskToken).build();
    service.blockingStub().respondDecisionTaskFailed(request);
  }

  public static PollForDecisionTaskResponse pollForDecisionTask(
      String namespace, TaskList tasklist, WorkflowServiceStubs service) throws Exception {
    PollForDecisionTaskRequest request =
        PollForDecisionTaskRequest.newBuilder()
            .setNamespace(namespace)
            .setTaskList(tasklist)
            .build();
    return service.blockingStub().pollForDecisionTask(request);
  }

  public static void signalWorkflow(
      WorkflowExecution workflowExecution, String namespace, WorkflowServiceStubs service)
      throws Exception {
    SignalWorkflowExecutionRequest signalRequest =
        SignalWorkflowExecutionRequest.newBuilder()
            .setNamespace(namespace)
            .setSignalName("my-signal")
            .setWorkflowExecution(workflowExecution)
            .build();
    service.blockingStub().signalWorkflowExecution(signalRequest);
  }
}
