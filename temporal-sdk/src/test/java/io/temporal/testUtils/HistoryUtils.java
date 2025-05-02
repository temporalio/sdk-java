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
            response, namespace, HOST_TASK_QUEUE, taskqueueName, workflowServiceStubs);
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
          String normalTaskQueueName,
          WorkflowServiceStubs service)
          throws Exception {
    // we send a signal that leads to creation of the new workflow task
    signalWorkflow(response.getWorkflowExecution(), namespace, service);
    respondWorkflowTaskCompletedWithSticky(
        response.getTaskToken(),
        createStickyTaskQueue(stickyTaskQueueName, normalTaskQueueName),
        service);
    return pollWorkflowTaskQueue(
        namespace, createStickyTaskQueue(stickyTaskQueueName, normalTaskQueueName), service);
  }
}
