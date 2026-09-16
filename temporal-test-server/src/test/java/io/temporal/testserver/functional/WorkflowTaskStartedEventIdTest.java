package io.temporal.testserver.functional;

import static io.temporal.internal.common.InternalUtils.createNormalTaskQueue;
import static org.junit.Assert.assertEquals;

import com.google.protobuf.util.Durations;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.TestServiceUtils;
import io.temporal.testserver.TestServer;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WorkflowTaskStartedEventIdTest {

  private final String NAMESPACE = "namespace";
  private final String TASK_QUEUE = "taskQueue";
  private final String WORKFLOW_TYPE = "wfType";

  private TestServer.InProcessTestServer testServer;
  private WorkflowServiceStubs workflowServiceStubs;

  @Before
  public void setUp() {
    this.testServer = TestServer.createServer(true);
    this.workflowServiceStubs =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setChannel(testServer.getChannel())
                .validateAndBuildWithDefaults());
  }

  @After
  public void tearDown() {
    this.workflowServiceStubs.shutdownNow();
    this.workflowServiceStubs.awaitTermination(1, TimeUnit.SECONDS);
    this.testServer.close();
  }

  @Test
  public void startedEventIdMatchesStartedEventWhenSignalArrivesBeforePoll() throws Exception {
    String workflowId = UUID.randomUUID().toString();
    workflowServiceStubs
        .blockingStub()
        .startWorkflowExecution(
            StartWorkflowExecutionRequest.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setNamespace(NAMESPACE)
                .setWorkflowId(workflowId)
                .setTaskQueue(createNormalTaskQueue(TASK_QUEUE))
                .setWorkflowRunTimeout(Durations.fromSeconds(100))
                .setWorkflowTaskTimeout(Durations.fromSeconds(100))
                .setWorkflowType(WorkflowType.newBuilder().setName(WORKFLOW_TYPE))
                .build());
    // The signal lands between WorkflowTaskScheduled and WorkflowTaskStarted.
    TestServiceUtils.signalWorkflow(
        WorkflowExecution.newBuilder().setWorkflowId(workflowId).build(),
        NAMESPACE,
        workflowServiceStubs);

    PollWorkflowTaskQueueResponse response =
        TestServiceUtils.pollWorkflowTaskQueue(
            NAMESPACE, createNormalTaskQueue(TASK_QUEUE), workflowServiceStubs);

    HistoryEvent started =
        response.getHistory().getEvents(response.getHistory().getEventsCount() - 1);
    assertEquals(EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED, started.getEventType());
    assertEquals(started.getEventId(), response.getStartedEventId());
  }
}
