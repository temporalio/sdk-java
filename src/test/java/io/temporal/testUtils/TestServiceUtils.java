/*
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
import io.temporal.*;
import io.temporal.internal.testservice.TestWorkflowService;
import java.util.ArrayList;
import java.util.UUID;

public class TestServiceUtils {
  private TestServiceUtils() {}

  public static void startWorkflowExecution(
      String domain, String tasklistName, String workflowType, TestWorkflowService service)
      throws Exception {
    startWorkflowExecution(domain, tasklistName, workflowType, 100, 100, service);
  }

  public static void startWorkflowExecution(
      String domain,
      String tasklistName,
      String workflowType,
      int executionStartToCloseTimeoutSeconds,
      int taskStartToCloseTimeoutSeconds,
      TestWorkflowService service)
      throws Exception {
    WorkflowType type = WorkflowType.newBuilder().setName(workflowType).build();
    StartWorkflowExecutionRequest request =
        StartWorkflowExecutionRequest.newBuilder()
            .setDomain(domain)
            .setRequestId(UUID.randomUUID().toString())
            .setWorkflowId(UUID.randomUUID().toString())
            .setTaskList(createNormalTaskList(tasklistName))
            .setExecutionStartToCloseTimeoutSeconds(executionStartToCloseTimeoutSeconds)
            .setTaskStartToCloseTimeoutSeconds(taskStartToCloseTimeoutSeconds)
            .setWorkflowType(type)
            .build();
    service.blockingStub().startWorkflowExecution(request);
  }

  public static void respondDecisionTaskCompletedWithSticky(
      ByteString taskToken, String stickyTasklistName, TestWorkflowService service)
      throws Exception {
    respondDecisionTaskCompletedWithSticky(taskToken, stickyTasklistName, 100, service);
  }

  public static void respondDecisionTaskCompletedWithSticky(
      ByteString taskToken,
      String stickyTasklistName,
      int startToCloseTimeout,
      TestWorkflowService service)
      throws Exception {
    StickyExecutionAttributes attributes =
        StickyExecutionAttributes.newBuilder()
            .setWorkerTaskList(createStickyTaskList(stickyTasklistName))
            .setScheduleToStartTimeoutSeconds(startToCloseTimeout)
            .build();
    RespondDecisionTaskCompletedRequest request =
        RespondDecisionTaskCompletedRequest.newBuilder()
            .setStickyAttributes(attributes)
            .setTaskToken(taskToken)
            .addAllDecisions(new ArrayList<>())
            .build();
    service.blockingStub().respondDecisionTaskCompleted(request);
  }

  public static void respondDecisionTaskFailedWithSticky(
      ByteString taskToken, TestWorkflowService service) throws Exception {
    RespondDecisionTaskFailedRequest request =
        RespondDecisionTaskFailedRequest.newBuilder().setTaskToken(taskToken).build();
    service.blockingStub().respondDecisionTaskFailed(request);
  }

  public static PollForDecisionTaskResponse pollForDecisionTask(
      String domain, TaskList tasklist, TestWorkflowService service) throws Exception {
    PollForDecisionTaskRequest request =
        PollForDecisionTaskRequest.newBuilder().setDomain(domain).setTaskList(tasklist).build();
    return service.blockingStub().pollForDecisionTask(request);
  }

  public static void signalWorkflow(
      WorkflowExecution workflowExecution, String domain, TestWorkflowService service)
      throws Exception {
    SignalWorkflowExecutionRequest signalRequest =
        SignalWorkflowExecutionRequest.newBuilder()
            .setDomain(domain)
            .setSignalName("my-signal")
            .setWorkflowExecution(workflowExecution)
            .build();
    service.blockingStub().signalWorkflowExecution(signalRequest);
  }
}
