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

package com.uber.cadence.testUtils;

import static com.uber.cadence.internal.common.InternalUtils.createNormalTaskList;
import static com.uber.cadence.internal.common.InternalUtils.createStickyTaskList;

import com.uber.cadence.*;
import com.uber.cadence.internal.testservice.TestWorkflowService;
import java.nio.ByteBuffer;
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
    StartWorkflowExecutionRequest request = new StartWorkflowExecutionRequest();
    request.domain = domain;
    request.workflowId = UUID.randomUUID().toString();
    request.taskList = createNormalTaskList(tasklistName);
    request.setExecutionStartToCloseTimeoutSeconds(executionStartToCloseTimeoutSeconds);
    request.setTaskStartToCloseTimeoutSeconds(taskStartToCloseTimeoutSeconds);
    WorkflowType type = new WorkflowType();
    type.name = workflowType;
    request.workflowType = type;
    service.StartWorkflowExecution(request);
  }

  public static void respondDecisionTaskCompletedWithSticky(
      ByteBuffer taskToken, String stickyTasklistName, TestWorkflowService service)
      throws Exception {
    respondDecisionTaskCompletedWithSticky(taskToken, stickyTasklistName, 100, service);
  }

  public static void respondDecisionTaskCompletedWithSticky(
      ByteBuffer taskToken,
      String stickyTasklistName,
      int startToCloseTimeout,
      TestWorkflowService service)
      throws Exception {
    RespondDecisionTaskCompletedRequest request = new RespondDecisionTaskCompletedRequest();
    StickyExecutionAttributes attributes = new StickyExecutionAttributes();
    attributes.setWorkerTaskList(createStickyTaskList(stickyTasklistName));
    attributes.setScheduleToStartTimeoutSeconds(startToCloseTimeout);
    request.setStickyAttributes(attributes);
    request.setTaskToken(taskToken);
    request.setDecisions(new ArrayList<>());
    service.RespondDecisionTaskCompleted(request);
  }

  public static void respondDecisionTaskFailedWithSticky(
      ByteBuffer taskToken, TestWorkflowService service) throws Exception {
    RespondDecisionTaskFailedRequest request = new RespondDecisionTaskFailedRequest();
    request.setTaskToken(taskToken);
    service.RespondDecisionTaskFailed(request);
  }

  public static PollForDecisionTaskResponse pollForDecisionTask(
      String domain, TaskList tasklist, TestWorkflowService service) throws Exception {
    PollForDecisionTaskRequest request = new PollForDecisionTaskRequest();
    request.setDomain(domain);
    request.setTaskList(tasklist);
    return service.PollForDecisionTask(request);
  }

  public static void signalWorkflow(
      WorkflowExecution workflowExecution, String domain, TestWorkflowService service)
      throws Exception {
    SignalWorkflowExecutionRequest signalRequest = new SignalWorkflowExecutionRequest();
    signalRequest.setDomain(domain);
    signalRequest.setSignalName("my-signal");
    signalRequest.setWorkflowExecution(workflowExecution);
    service.SignalWorkflowExecution(signalRequest);
  }
}
