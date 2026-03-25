package io.temporal.internal.replay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import io.temporal.api.common.v1.Header;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.WorkflowTaskFailedCause;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.protocol.v1.Message;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest;
import io.temporal.common.VersioningBehavior;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.Issue;
import io.temporal.internal.common.SdkFlag;
import io.temporal.internal.history.VersionMarkerUtils;
import io.temporal.internal.statemachines.UpdateProtocolCallback;
import io.temporal.internal.statemachines.WorkflowStateMachines;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.internal.worker.WorkflowExecutorCache;
import io.temporal.internal.worker.WorkflowRunLockManager;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testing.WorkflowHistoryLoader;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

@Issue("https://github.com/temporalio/sdk-java/issues/2796")
public class GetVersionInterleavedUpdateReplayTaskHandlerTest {

  private static final String CHANGE_ID_1 = "ChangeId1";
  private static final String CHANGE_ID_2 = "ChangeId2";
  private static final String HISTORY_RESOURCE_NAME = "testGetVersionInterleavedUpdateReplay.json";
  private static final long FAILED_WFT_STARTED_EVENT_ID = 11L;
  private static final long PREVIOUS_STARTED_EVENT_ID = 3L;
  private static final long STALE_HISTORY_LAST_EVENT_ID = 9L;
  private static final Payloads UPDATE_RESULT =
      DefaultDataConverter.STANDARD_INSTANCE.toPayloads("works").get();

  @Test
  public void staleFullHistoryForInterleavedUpdateVersionTaskFailsReplayDeterministically()
      throws Exception {
    WorkflowExecutionHistory capturedHistory =
        WorkflowHistoryLoader.readHistoryFromResource(HISTORY_RESOURCE_NAME);
    assertTrue(
        "Captured history must contain UpdateCompleted between version markers.",
        hasInterleavedUpdateCompletedBetweenVersionMarkers(capturedHistory));

    PollWorkflowTaskQueueResponse workflowTask =
        PollWorkflowTaskQueueResponse.newBuilder()
            .setTaskToken(ByteString.copyFromUtf8("task-token"))
            .setWorkflowExecution(capturedHistory.getWorkflowExecution())
            .setWorkflowType(WorkflowType.newBuilder().setName("GreetingWorkflow").build())
            .addMessages(acceptedUpdateRequestMessage(capturedHistory))
            .setStartedEventId(FAILED_WFT_STARTED_EVENT_ID)
            .setPreviousStartedEventId(PREVIOUS_STARTED_EVENT_ID)
            .setAttempt(1)
            .build();

    WorkflowServiceStubs service =
        mockWorkflowService(historyUpToEventId(capturedHistory, STALE_HISTORY_LAST_EVENT_ID));
    WorkflowExecutorCache cache =
        new WorkflowExecutorCache(10, new WorkflowRunLockManager(), new NoopScope());
    List<SdkFlag> savedInitialFlags = WorkflowStateMachines.initialFlags;

    try {
      WorkflowStateMachines.initialFlags =
          Collections.unmodifiableList(
              Arrays.asList(SdkFlag.SKIP_YIELD_ON_DEFAULT_VERSION, SdkFlag.SKIP_YIELD_ON_VERSION));

      ReplayWorkflowTaskHandler taskHandler =
          new ReplayWorkflowTaskHandler(
              "UnitTest",
              new InterleavedUpdateReplayWorkflowFactory(),
              cache,
              SingleWorkerOptions.newBuilder().setIdentity("test-worker").build(),
              null,
              Duration.ZERO,
              service,
              null);

      io.temporal.internal.worker.WorkflowTaskHandler.Result result =
          taskHandler.handleWorkflowTask(workflowTask);

      RespondWorkflowTaskFailedRequest taskFailed = result.getTaskFailed();
      assertNotNull("Expected task failure result.", taskFailed);
      assertEquals(
          WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_WORKFLOW_WORKER_UNHANDLED_FAILURE,
          taskFailed.getCause());
      assertEquals(
          IllegalStateException.class.getName(),
          taskFailed.getFailure().getApplicationFailureInfo().getType());
      assertEquals(
          "Premature end of stream, expectedLastEventID=11 but no more events after eventID=9",
          taskFailed.getFailure().getMessage());
    } finally {
      WorkflowStateMachines.initialFlags = savedInitialFlags;
    }
  }

  private static WorkflowServiceStubs mockWorkflowService(History staleFullHistory) {
    WorkflowServiceStubs service = mock(WorkflowServiceStubs.class);
    when(service.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.newBuilder().build());

    io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceBlockingStub
        blockingStub =
            mock(
                io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceBlockingStub
                    .class);
    when(service.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);
    when(blockingStub.getWorkflowExecutionHistory(any()))
        .thenReturn(
            GetWorkflowExecutionHistoryResponse.newBuilder().setHistory(staleFullHistory).build());
    return service;
  }

  private static History historyUpToEventId(WorkflowExecutionHistory history, long lastEventId) {
    History.Builder result = History.newBuilder();
    for (HistoryEvent event : history.getEvents()) {
      if (event.getEventId() > lastEventId) {
        break;
      }
      if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED) {
        result.addEvents(
            event.toBuilder()
                .setWorkflowExecutionUpdateAcceptedEventAttributes(
                    event.getWorkflowExecutionUpdateAcceptedEventAttributes().toBuilder()
                        .clearAcceptedRequest()
                        .clearAcceptedRequestMessageId()
                        .clearAcceptedRequestSequencingEventId())
                .build());
        continue;
      }
      result.addEvents(event);
    }
    return result.build();
  }

  private static Message acceptedUpdateRequestMessage(WorkflowExecutionHistory history) {
    for (HistoryEvent event : history.getEvents()) {
      if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED) {
        io.temporal.api.history.v1.WorkflowExecutionUpdateAcceptedEventAttributes acceptedEvent =
            event.getWorkflowExecutionUpdateAcceptedEventAttributes();
        return Message.newBuilder()
            .setId(acceptedEvent.getAcceptedRequestMessageId())
            .setProtocolInstanceId(acceptedEvent.getProtocolInstanceId())
            .setEventId(acceptedEvent.getAcceptedRequestSequencingEventId())
            .setBody(Any.pack(acceptedEvent.getAcceptedRequest()))
            .build();
      }
    }
    throw new AssertionError("Expected captured history to contain an update accepted event.");
  }

  private static boolean hasInterleavedUpdateCompletedBetweenVersionMarkers(
      WorkflowExecutionHistory history) {
    boolean sawFirstMarker = false;
    boolean sawUpdateCompleted = false;

    for (HistoryEvent event : history.getEvents()) {
      if (event.getEventType() == EventType.EVENT_TYPE_MARKER_RECORDED
          && VersionMarkerUtils.hasVersionMarkerStructure(event)) {
        String changeId = VersionMarkerUtils.getChangeId(event.getMarkerRecordedEventAttributes());
        if (CHANGE_ID_1.equals(changeId)) {
          sawFirstMarker = true;
          continue;
        }
        if (CHANGE_ID_2.equals(changeId) && sawFirstMarker && sawUpdateCompleted) {
          return true;
        }
      }

      if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED
          && sawFirstMarker) {
        sawUpdateCompleted = true;
      }
    }

    return false;
  }

  private static final class InterleavedUpdateReplayWorkflowFactory
      implements ReplayWorkflowFactory {

    @Override
    public ReplayWorkflow getWorkflow(
        WorkflowType workflowType, WorkflowExecution workflowExecution) throws Exception {
      return new InterleavedUpdateReplayWorkflow();
    }

    @Override
    public boolean isAnyTypeSupported() {
      return false;
    }
  }

  private static final class InterleavedUpdateReplayWorkflow implements ReplayWorkflow {
    private ReplayWorkflowContext replayContext;
    private UpdateProtocolCallback updateCallbacks;
    private boolean commandsIssued;

    @Override
    public void start(HistoryEvent event, ReplayWorkflowContext context) {
      this.replayContext = context;
    }

    @Override
    public void handleSignal(
        String signalName, Optional<Payloads> input, long eventId, Header header) {}

    @Override
    public void handleUpdate(
        String updateName,
        String updateId,
        Optional<Payloads> input,
        long eventId,
        Header header,
        UpdateProtocolCallback callbacks) {
      if (updateCallbacks == null) {
        updateCallbacks = callbacks;
      }
    }

    @Override
    public boolean eventLoop() {
      if (!commandsIssued && updateCallbacks != null) {
        commandsIssued = true;
        updateCallbacks.accept();
        replayContext.sideEffect(() -> Optional.empty(), null, ignored -> {});
        replayContext.getVersion(
            CHANGE_ID_1, Workflow.DEFAULT_VERSION, 1, (version, failure) -> {});
        updateCallbacks.complete(Optional.of(UPDATE_RESULT), null);
        replayContext.getVersion(
            CHANGE_ID_2, Workflow.DEFAULT_VERSION, 1, (version, failure) -> {});
      }
      return false;
    }

    @Override
    public Optional<Payloads> getOutput() {
      return Optional.empty();
    }

    @Override
    public void cancel(String reason) {}

    @Override
    public void close() {}

    @Override
    public Optional<Payloads> query(WorkflowQuery query) {
      return Optional.empty();
    }

    @Override
    public WorkflowContext getWorkflowContext() {
      WorkflowContext workflowContext = mock(WorkflowContext.class);
      when(workflowContext.getWorkflowImplementationOptions())
          .thenReturn(WorkflowImplementationOptions.getDefaultInstance());
      when(workflowContext.mapWorkflowExceptionToFailure(any()))
          .thenAnswer(
              invocation ->
                  DefaultDataConverter.STANDARD_INSTANCE.exceptionToFailure(
                      invocation.getArgument(0)));
      when(workflowContext.getRunningSignalHandlers()).thenReturn(Collections.emptyMap());
      when(workflowContext.getRunningUpdateHandlers()).thenReturn(Collections.emptyMap());
      when(workflowContext.getVersioningBehavior()).thenReturn(VersioningBehavior.UNSPECIFIED);
      return workflowContext;
    }
  }
}
