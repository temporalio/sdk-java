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

package io.temporal.testUtils;

import static io.temporal.internal.common.InternalUtils.createNormalTaskQueue;
import static io.temporal.internal.common.InternalUtils.createStickyTaskQueue;
import static io.temporal.testing.internal.TestServiceUtils.*;

import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testserver.TestServer;
import java.util.concurrent.TimeUnit;

public class HistoryUtils {
  private HistoryUtils() {}

  public static final String NAMESPACE = "namespace";
  public static final String TASK_QUEUE = "taskQueue";
  public static final String HOST_TASK_QUEUE = "stickyTaskQueue";
  public static final String WORKFLOW_TYPE = "workflowType";

  public static PollWorkflowTaskQueueResponse generateWorkflowTaskWithInitialHistory()
      throws Exception {
    try (TestServer.InProcessTestServer server = TestServer.createServer(true)) {
      WorkflowServiceStubs workflowServiceStubs =
          WorkflowServiceStubs.newServiceStubs(
              WorkflowServiceStubsOptions.newBuilder().setChannel(server.getChannel()).build());
      try {
        return generateWorkflowTaskWithInitialHistory(
            NAMESPACE, TASK_QUEUE, WORKFLOW_TYPE, workflowServiceStubs);
      } finally {
        workflowServiceStubs.shutdownNow();
        workflowServiceStubs.awaitTermination(1, TimeUnit.SECONDS);
      }
    }
  }

  public static PollWorkflowTaskQueueResponse generateWorkflowTaskWithInitialHistory(
      String namespace, String taskqueueName, String workflowType, WorkflowServiceStubs service)
      throws Exception {
    startWorkflowExecution(namespace, taskqueueName, workflowType, service);
    return pollWorkflowTaskQueue(namespace, createNormalTaskQueue(taskqueueName), service);
  }

  public static PollWorkflowTaskQueueResponse generateWorkflowTaskWithPartialHistory()
      throws Exception {
    return generateWorkflowTaskWithPartialHistory(NAMESPACE, TASK_QUEUE, WORKFLOW_TYPE);
  }

  public static PollWorkflowTaskQueueResponse generateWorkflowTaskWithPartialHistory(
      String namespace, String taskqueueName, String workflowType) throws Exception {
    try (TestServer.InProcessTestServer server = TestServer.createServer(true)) {
      WorkflowServiceStubs workflowServiceStubs =
          WorkflowServiceStubs.newServiceStubs(
              WorkflowServiceStubsOptions.newBuilder().setChannel(server.getChannel()).build());
      try {
        PollWorkflowTaskQueueResponse response =
            generateWorkflowTaskWithInitialHistory(
                namespace, taskqueueName, workflowType, workflowServiceStubs);
        return generateWorkflowTaskWithPartialHistoryFromExistingTask(
            response, namespace, HOST_TASK_QUEUE, workflowServiceStubs);
      } finally {
        workflowServiceStubs.shutdownNow();
        workflowServiceStubs.awaitTermination(1, TimeUnit.SECONDS);
      }
    }
  }

  public static PollWorkflowTaskQueueResponse
      generateWorkflowTaskWithPartialHistoryFromExistingTask(
          PollWorkflowTaskQueueResponse response,
          String namespace,
          String stickyTaskQueueName,
          WorkflowServiceStubs service)
          throws Exception {
    signalWorkflow(response.getWorkflowExecution(), namespace, service);
    respondWorkflowTaskCompletedWithSticky(response.getTaskToken(), stickyTaskQueueName, service);
    return pollWorkflowTaskQueue(namespace, createStickyTaskQueue(stickyTaskQueueName), service);
  }
}
