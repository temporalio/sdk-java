package io.temporal.internal.replay;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.protocol.v1.Message;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.internal.Issue;
import io.temporal.internal.common.SdkFlag;
import io.temporal.internal.history.VersionMarkerUtils;
import io.temporal.internal.statemachines.WorkflowStateMachines;
import io.temporal.internal.sync.POJOWorkflowImplementationFactory;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.internal.worker.WorkflowExecutorCache;
import io.temporal.internal.worker.WorkflowRunLockManager;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testing.WorkflowHistoryLoader;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.versionTests.GetVersionInterleavedUpdateReplayTest.GreetingWorkflowImpl;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

@Issue("https://github.com/temporalio/sdk-java/issues/2796")
public class GetVersionInterleavedUpdateReplayTaskHandlerTest {

  private static final String CHANGE_ID_1 = "ChangeId1";
  private static final String CHANGE_ID_2 = "ChangeId2";
  private static final String HISTORY_RESOURCE_NAME = "testGetVersionInterleavedUpdateReplay.json";
  private static final long FAILED_WFT_STARTED_EVENT_ID = 11L;
  private static final long PREVIOUS_STARTED_EVENT_ID = 3L;
  private static final long STALE_HISTORY_LAST_EVENT_ID = 9L;

  @Test
  public void staleFullHistoryForInterleavedUpdateVersionTaskRecoversReplayDeterministically()
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
            .addAllMessages(acceptedUpdateRequestMessages(capturedHistory))
            .setStartedEventId(FAILED_WFT_STARTED_EVENT_ID)
            .setPreviousStartedEventId(PREVIOUS_STARTED_EVENT_ID)
            .setAttempt(1)
            .build();

    WorkflowServiceStubs service =
        mockWorkflowService(
            historyUpToEventId(capturedHistory, STALE_HISTORY_LAST_EVENT_ID),
            historyUpToEventId(capturedHistory, Long.MAX_VALUE));
    WorkflowExecutorCache cache =
        new WorkflowExecutorCache(10, new WorkflowRunLockManager(), new NoopScope());
    List<SdkFlag> savedInitialFlags = WorkflowStateMachines.initialFlags;
    SingleWorkerOptions workerOptions =
        SingleWorkerOptions.newBuilder()
            .setIdentity("test-worker")
            .setDefaultDeadlockDetectionTimeout(5_000)
            .build();
    ExecutorService workflowThreads = Executors.newCachedThreadPool();

    try {
      WorkflowStateMachines.initialFlags =
          Collections.unmodifiableList(
              Arrays.asList(SdkFlag.SKIP_YIELD_ON_DEFAULT_VERSION, SdkFlag.SKIP_YIELD_ON_VERSION));

      POJOWorkflowImplementationFactory workflowFactory =
          new POJOWorkflowImplementationFactory(
              workerOptions, workflowThreads::submit, new WorkerInterceptor[0], cache, "UnitTest");
      workflowFactory.registerWorkflowImplementationTypes(
          WorkflowImplementationOptions.getDefaultInstance(),
          new Class<?>[] {GreetingWorkflowImpl.class});

      ReplayWorkflowTaskHandler taskHandler =
          new ReplayWorkflowTaskHandler(
              "UnitTest",
              workflowFactory,
              cache,
              workerOptions,
              null,
              Duration.ZERO,
              service,
              null);

      io.temporal.internal.worker.WorkflowTaskHandler.Result result =
          taskHandler.handleWorkflowTask(workflowTask);

      assertNull(
          "Expected replay to recover instead of failing the workflow task.",
          result.getTaskFailed());
      assertNotNull("Expected successful workflow task completion.", result.getTaskCompleted());
    } finally {
      WorkflowStateMachines.initialFlags = savedInitialFlags;
      workflowThreads.shutdownNow();
      workflowThreads.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static WorkflowServiceStubs mockWorkflowService(History... fullHistoryAttempts) {
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
    GetWorkflowExecutionHistoryResponse[] responses =
        new GetWorkflowExecutionHistoryResponse[fullHistoryAttempts.length];
    for (int i = 0; i < fullHistoryAttempts.length; i++) {
      responses[i] =
          GetWorkflowExecutionHistoryResponse.newBuilder()
              .setHistory(fullHistoryAttempts[i])
              .build();
    }
    when(blockingStub.getWorkflowExecutionHistory(any()))
        .thenReturn(responses[0], Arrays.copyOfRange(responses, 1, responses.length));
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

  private static List<Message> acceptedUpdateRequestMessages(WorkflowExecutionHistory history) {
    List<Message> result = new java.util.ArrayList<>();
    for (HistoryEvent event : history.getEvents()) {
      if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED) {
        io.temporal.api.history.v1.WorkflowExecutionUpdateAcceptedEventAttributes acceptedEvent =
            event.getWorkflowExecutionUpdateAcceptedEventAttributes();
        result.add(
            Message.newBuilder()
                .setId(acceptedEvent.getAcceptedRequestMessageId())
                .setProtocolInstanceId(acceptedEvent.getProtocolInstanceId())
                .setEventId(acceptedEvent.getAcceptedRequestSequencingEventId())
                .setBody(Any.pack(acceptedEvent.getAcceptedRequest()))
                .build());
      }
    }
    if (!result.isEmpty()) {
      return result;
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
}
