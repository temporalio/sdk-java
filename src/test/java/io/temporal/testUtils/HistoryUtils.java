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
import static io.temporal.testUtils.TestServiceUtils.*;

import io.temporal.internal.testservice.TestWorkflowService;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflowservice.v1.PollForDecisionTaskResponse;
import java.util.concurrent.TimeUnit;

public class HistoryUtils {
  private HistoryUtils() {}

  private static final String NAMESPACE = "namespace";
  private static final String TASK_QUEUE = "taskQueue";
  private static final String HOST_TASK_QUEUE = "stickyTaskQueue";
  private static final String WORKFLOW_TYPE = "workflowType";

  public static PollForDecisionTaskResponse generateDecisionTaskWithInitialHistory()
      throws Exception {
    TestWorkflowService testService = new TestWorkflowService(true);
    WorkflowServiceStubs service = testService.newClientStub();
    try {
      return generateDecisionTaskWithInitialHistory(NAMESPACE, TASK_QUEUE, WORKFLOW_TYPE, service);
    } finally {
      service.shutdownNow();
      service.awaitTermination(1, TimeUnit.SECONDS);
      testService.close();
    }
  }

  public static PollForDecisionTaskResponse generateDecisionTaskWithInitialHistory(
      String namespace, String taskqueueName, String workflowType, WorkflowServiceStubs service)
      throws Exception {
    startWorkflowExecution(namespace, taskqueueName, workflowType, service);
    return pollForDecisionTask(namespace, createNormalTaskQueue(taskqueueName), service);
  }

  public static PollForDecisionTaskResponse generateDecisionTaskWithPartialHistory()
      throws Exception {
    return generateDecisionTaskWithPartialHistory(NAMESPACE, TASK_QUEUE, WORKFLOW_TYPE);
  }

  public static PollForDecisionTaskResponse generateDecisionTaskWithPartialHistory(
      String namespace, String taskqueueName, String workflowType) throws Exception {
    TestWorkflowService testService = new TestWorkflowService(true);
    WorkflowServiceStubs service = testService.newClientStub();
    try {
      PollForDecisionTaskResponse response =
          generateDecisionTaskWithInitialHistory(namespace, taskqueueName, workflowType, service);
      return generateDecisionTaskWithPartialHistoryFromExistingTask(
          response, namespace, HOST_TASK_QUEUE, service);
    } finally {
      service.shutdownNow();
      service.awaitTermination(1, TimeUnit.SECONDS);
      testService.close();
    }
  }

  public static PollForDecisionTaskResponse generateDecisionTaskWithPartialHistoryFromExistingTask(
      PollForDecisionTaskResponse response,
      String namespace,
      String stickyTaskQueueName,
      WorkflowServiceStubs service)
      throws Exception {
    signalWorkflow(response.getWorkflowExecution(), namespace, service);
    respondDecisionTaskCompletedWithSticky(response.getTaskToken(), stickyTaskQueueName, service);
    return pollForDecisionTask(namespace, createStickyTaskQueue(stickyTaskQueueName), service);
  }
}
