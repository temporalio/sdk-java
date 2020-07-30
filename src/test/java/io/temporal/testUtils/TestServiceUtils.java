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

import static io.temporal.internal.common.InternalUtils.createNormalTaskQueue;
import static io.temporal.internal.common.InternalUtils.createStickyTaskQueue;

import com.google.protobuf.ByteString;
import com.google.protobuf.util.Durations;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.taskqueue.v1.StickyExecutionAttributes;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.ArrayList;
import java.util.UUID;

public class TestServiceUtils {
  private TestServiceUtils() {}

  public static void startWorkflowExecution(
      String namespace, String taskqueueName, String workflowType, WorkflowServiceStubs service)
      throws Exception {
    startWorkflowExecution(namespace, taskqueueName, workflowType, 100, 100, service);
  }

  public static void startWorkflowExecution(
      String namespace,
      String taskqueueName,
      String workflowType,
      int workflowRunTimeoutSeconds,
      int workflowTaskTimeoutSeconds,
      WorkflowServiceStubs service)
      throws Exception {
    StartWorkflowExecutionRequest.Builder request = StartWorkflowExecutionRequest.newBuilder();
    request.setRequestId(UUID.randomUUID().toString());
    request.setNamespace(namespace);
    request.setWorkflowId(UUID.randomUUID().toString());
    request.setTaskQueue(createNormalTaskQueue(taskqueueName));
    request.setWorkflowRunTimeout(Durations.fromSeconds(workflowRunTimeoutSeconds));
    request.setWorkflowTaskTimeout(Durations.fromSeconds(workflowTaskTimeoutSeconds));
    request.setWorkflowType(WorkflowType.newBuilder().setName(workflowType));
    service.blockingStub().startWorkflowExecution(request.build());
  }

  public static void respondWorkflowTaskCompletedWithSticky(
      ByteString taskToken, String stickyTaskqueueName, WorkflowServiceStubs service)
      throws Exception {
    respondWorkflowTaskCompletedWithSticky(taskToken, stickyTaskqueueName, 100, service);
  }

  public static void respondWorkflowTaskCompletedWithSticky(
      ByteString taskToken,
      String stickyTaskqueueName,
      int startToCloseTimeout,
      WorkflowServiceStubs service)
      throws Exception {
    RespondWorkflowTaskCompletedRequest.Builder request =
        RespondWorkflowTaskCompletedRequest.newBuilder();
    StickyExecutionAttributes.Builder attributes = StickyExecutionAttributes.newBuilder();
    attributes.setWorkerTaskQueue(createStickyTaskQueue(stickyTaskqueueName));
    attributes.setScheduleToStartTimeout(Durations.fromSeconds(startToCloseTimeout));
    request.setStickyAttributes(attributes);
    request.setTaskToken(taskToken);
    request.addAllCommands(new ArrayList<>());
    service.blockingStub().respondWorkflowTaskCompleted(request.build());
  }

  public static void respondWorkflowTaskFailedWithSticky(
      ByteString taskToken, WorkflowServiceStubs service) throws Exception {
    RespondWorkflowTaskFailedRequest request =
        RespondWorkflowTaskFailedRequest.newBuilder().setTaskToken(taskToken).build();
    service.blockingStub().respondWorkflowTaskFailed(request);
  }

  public static PollWorkflowTaskQueueResponse pollWorkflowTaskQueue(
      String namespace, TaskQueue taskqueue, WorkflowServiceStubs service) throws Exception {
    PollWorkflowTaskQueueRequest request =
        PollWorkflowTaskQueueRequest.newBuilder()
            .setNamespace(namespace)
            .setTaskQueue(taskqueue)
            .build();
    return service.blockingStub().pollWorkflowTaskQueue(request);
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
