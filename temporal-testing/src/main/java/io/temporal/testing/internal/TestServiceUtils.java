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

package io.temporal.testing.internal;

import static io.temporal.internal.common.InternalUtils.createNormalTaskQueue;

import com.google.protobuf.ByteString;
import io.nexusrpc.handler.ServiceImplInstance;
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
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.NexusServiceOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TestServiceUtils {
  private TestServiceUtils() {}

  public static WorkflowImplementationOptions applyNexusServiceOptions(
      WorkflowImplementationOptions options,
      Object[] nexusServiceImplementations,
      String endpoint) {
    Map<String, NexusServiceOptions> newNexusServiceOptions = new HashMap<>();
    for (Object nexusService : nexusServiceImplementations) {
      String serviceName = ServiceImplInstance.fromInstance(nexusService).getDefinition().getName();
      NexusServiceOptions serviceOptionWithEndpoint =
          options.getNexusServiceOptions().get(serviceName);
      if (serviceOptionWithEndpoint == null) {
        serviceOptionWithEndpoint = NexusServiceOptions.newBuilder().build();
      }
      serviceOptionWithEndpoint =
          serviceOptionWithEndpoint.getEndpoint() == null
              ? NexusServiceOptions.newBuilder(serviceOptionWithEndpoint)
                  .setEndpoint(endpoint)
                  .build()
              : serviceOptionWithEndpoint;
      newNexusServiceOptions.put(serviceName, serviceOptionWithEndpoint);
    }
    NexusServiceOptions defaultServiceOptions =
        options.getDefaultNexusServiceOptions() == null
            ? NexusServiceOptions.newBuilder().build()
            : options.getDefaultNexusServiceOptions();
    if (defaultServiceOptions.getEndpoint() == null) {
      options =
          options.toBuilder()
              .setDefaultNexusServiceOptions(
                  NexusServiceOptions.newBuilder(defaultServiceOptions)
                      .setEndpoint(endpoint)
                      .build())
              .build();
    }
    return options.toBuilder().setNexusServiceOptions(newNexusServiceOptions).build();
  }

  public static void startWorkflowExecution(
      String namespace, String taskqueueName, String workflowType, WorkflowServiceStubs service)
      throws Exception {
    startWorkflowExecution(
        namespace,
        taskqueueName,
        workflowType,
        Duration.ofSeconds(100),
        Duration.ofSeconds(100),
        service);
  }

  public static void startWorkflowExecution(
      String namespace,
      String taskqueueName,
      String workflowType,
      Duration workflowRunTimeout,
      Duration workflowTaskTimeout,
      WorkflowServiceStubs service)
      throws Exception {
    StartWorkflowExecutionRequest.Builder request = StartWorkflowExecutionRequest.newBuilder();
    request.setRequestId(UUID.randomUUID().toString());
    request.setNamespace(namespace);
    request.setWorkflowId(UUID.randomUUID().toString());
    request.setTaskQueue(createNormalTaskQueue(taskqueueName));
    request.setWorkflowRunTimeout(ProtobufTimeUtils.toProtoDuration(workflowRunTimeout));
    request.setWorkflowTaskTimeout(ProtobufTimeUtils.toProtoDuration(workflowTaskTimeout));
    request.setWorkflowType(WorkflowType.newBuilder().setName(workflowType));
    service.blockingStub().startWorkflowExecution(request.build());
  }

  public static void respondWorkflowTaskCompletedWithSticky(
      ByteString taskToken, TaskQueue stickyTaskQueue, WorkflowServiceStubs service)
      throws Exception {
    respondWorkflowTaskCompletedWithSticky(
        taskToken, stickyTaskQueue, Duration.ofSeconds(100), service);
  }

  public static void respondWorkflowTaskCompletedWithSticky(
      ByteString taskToken,
      TaskQueue stickyTaskQueue,
      Duration startToCloseTimeout,
      WorkflowServiceStubs service)
      throws Exception {
    RespondWorkflowTaskCompletedRequest.Builder request =
        RespondWorkflowTaskCompletedRequest.newBuilder();
    StickyExecutionAttributes.Builder attributes = StickyExecutionAttributes.newBuilder();
    attributes.setWorkerTaskQueue(stickyTaskQueue);
    attributes.setScheduleToStartTimeout(ProtobufTimeUtils.toProtoDuration(startToCloseTimeout));
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
