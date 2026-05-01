package io.temporal.testserver.functional;

import static io.temporal.internal.common.InternalUtils.createNormalTaskQueue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.RecordMarkerCommandAttributes;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.SuggestContinueAsNewReason;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowTaskStartedEventAttributes;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.TestServiceUtils;
import io.temporal.testserver.TestServer;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WorkflowTaskStartedAttributesTest {

  private static final long HISTORY_SIZE_SUGGEST_CONTINUE_AS_NEW = 4L * 1024 * 1024;
  private static final long HISTORY_COUNT_SUGGEST_CONTINUE_AS_NEW = 4L * 1024;
  private static final int MARKER_PAYLOAD_SIZE = 1024 * 1024;
  private static final int MARKER_ITERATIONS = 5;

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
  public void firstWorkflowTaskStartedReportsHistorySizeWithoutSuggestion() throws Exception {
    TestServiceUtils.startWorkflowExecution(
        NAMESPACE, TASK_QUEUE, WORKFLOW_TYPE, workflowServiceStubs);

    PollWorkflowTaskQueueResponse response =
        TestServiceUtils.pollWorkflowTaskQueue(
            NAMESPACE, createNormalTaskQueue(TASK_QUEUE), workflowServiceStubs);

    WorkflowTaskStartedEventAttributes startedAttributes = lastStartedAttributes(response);
    assertTrue(startedAttributes.getHistorySizeBytes() > 0);
    assertFalse(startedAttributes.getSuggestContinueAsNew());
    assertTrue(startedAttributes.getSuggestContinueAsNewReasonsList().isEmpty());
  }

  @Test
  public void historySizeAboveThresholdSuggestsContinueAsNew() throws Exception {
    TestServiceUtils.startWorkflowExecution(
        NAMESPACE, TASK_QUEUE, WORKFLOW_TYPE, workflowServiceStubs);

    PollWorkflowTaskQueueResponse response =
        TestServiceUtils.pollWorkflowTaskQueue(
            NAMESPACE, createNormalTaskQueue(TASK_QUEUE), workflowServiceStubs);

    for (int i = 0; i < MARKER_ITERATIONS; i++) {
      workflowServiceStubs
          .blockingStub()
          .respondWorkflowTaskCompleted(
              RespondWorkflowTaskCompletedRequest.newBuilder()
                  .setTaskToken(response.getTaskToken())
                  .addCommands(newLargeMarkerCommand())
                  .build());
      TestServiceUtils.signalWorkflow(
          response.getWorkflowExecution(), NAMESPACE, workflowServiceStubs);
      response =
          TestServiceUtils.pollWorkflowTaskQueue(
              NAMESPACE, createNormalTaskQueue(TASK_QUEUE), workflowServiceStubs);
    }

    WorkflowTaskStartedEventAttributes startedAttributes = lastStartedAttributes(response);
    assertTrue(
        "Expected history >= 4 MiB but was " + startedAttributes.getHistorySizeBytes(),
        startedAttributes.getHistorySizeBytes() >= HISTORY_SIZE_SUGGEST_CONTINUE_AS_NEW);
    assertSuggestsContinueAsNew(
        startedAttributes,
        SuggestContinueAsNewReason.SUGGEST_CONTINUE_AS_NEW_REASON_HISTORY_SIZE_TOO_LARGE);
  }

  @Test
  public void historyCountAboveThresholdSuggestsContinueAsNew() throws Exception {
    TestServiceUtils.startWorkflowExecution(
        NAMESPACE, TASK_QUEUE, WORKFLOW_TYPE, workflowServiceStubs);

    PollWorkflowTaskQueueResponse response =
        TestServiceUtils.pollWorkflowTaskQueue(
            NAMESPACE, createNormalTaskQueue(TASK_QUEUE), workflowServiceStubs);

    RespondWorkflowTaskCompletedRequest.Builder completedRequest =
        RespondWorkflowTaskCompletedRequest.newBuilder().setTaskToken(response.getTaskToken());
    for (int i = 0; i < HISTORY_COUNT_SUGGEST_CONTINUE_AS_NEW; i++) {
      completedRequest.addCommands(newMarkerCommand());
    }
    workflowServiceStubs.blockingStub().respondWorkflowTaskCompleted(completedRequest.build());
    TestServiceUtils.signalWorkflow(
        response.getWorkflowExecution(), NAMESPACE, workflowServiceStubs);

    response =
        TestServiceUtils.pollWorkflowTaskQueue(
            NAMESPACE, createNormalTaskQueue(TASK_QUEUE), workflowServiceStubs);

    WorkflowTaskStartedEventAttributes startedAttributes = lastStartedAttributes(response);
    assertSuggestsContinueAsNew(
        startedAttributes,
        SuggestContinueAsNewReason.SUGGEST_CONTINUE_AS_NEW_REASON_TOO_MANY_HISTORY_EVENTS);
  }

  private static WorkflowTaskStartedEventAttributes lastStartedAttributes(
      PollWorkflowTaskQueueResponse response) {
    HistoryEvent last = response.getHistory().getEvents(response.getHistory().getEventsCount() - 1);
    assertEquals(EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED, last.getEventType());
    return last.getWorkflowTaskStartedEventAttributes();
  }

  private static void assertSuggestsContinueAsNew(
      WorkflowTaskStartedEventAttributes startedAttributes, SuggestContinueAsNewReason reason) {
    assertTrue(startedAttributes.getSuggestContinueAsNew());
    assertTrue(startedAttributes.getSuggestContinueAsNewReasonsList().contains(reason));
  }

  private static Command newLargeMarkerCommand() {
    ByteString markerData = ByteString.copyFrom(new byte[MARKER_PAYLOAD_SIZE]);
    Payloads markerPayloads =
        Payloads.newBuilder().addPayloads(Payload.newBuilder().setData(markerData)).build();
    return newMarkerCommand(markerPayloads);
  }

  private static Command newMarkerCommand() {
    return newMarkerCommand(Payloads.getDefaultInstance());
  }

  private static Command newMarkerCommand(Payloads markerPayloads) {
    return Command.newBuilder()
        .setCommandType(CommandType.COMMAND_TYPE_RECORD_MARKER)
        .setRecordMarkerCommandAttributes(
            RecordMarkerCommandAttributes.newBuilder()
                .setMarkerName("large-history-marker")
                .putDetails("payload", markerPayloads))
        .build();
  }
}
