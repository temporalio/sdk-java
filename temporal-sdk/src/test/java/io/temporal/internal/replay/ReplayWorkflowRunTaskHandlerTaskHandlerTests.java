package io.temporal.internal.replay;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.util.Durations;
import com.uber.m3.tally.NoopScope;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.taskqueue.v1.StickyExecutionAttributes;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.common.CancellationToken;
import io.temporal.internal.common.InternalUtils;
import io.temporal.internal.payload.storage.ExternalStorageRunner;
import io.temporal.internal.statemachines.ExecuteLocalActivityParameters;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.internal.worker.WorkflowExecutorCache;
import io.temporal.internal.worker.WorkflowRunLockManager;
import io.temporal.internal.worker.WorkflowTaskHandler;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.serviceclient.Version;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testUtils.HistoryUtils;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class ReplayWorkflowRunTaskHandlerTaskHandlerTests {

  @Rule public SDKTestWorkflowRule testWorkflowRule = SDKTestWorkflowRule.newBuilder().build();

  @Test
  public void ifStickyExecutionAttributesAreNotSetThenWorkflowsAreNotCached() throws Throwable {
    assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);

    // Arrange
    WorkflowExecutorCache cache =
        new WorkflowExecutorCache(10, new WorkflowRunLockManager(), new NoopScope());
    WorkflowTaskHandler taskHandler =
        new ReplayWorkflowTaskHandler(
            "namespace",
            setUpMockWorkflowFactory(),
            cache,
            SingleWorkerOptions.newBuilder().build(),
            null,
            Duration.ofSeconds(5),
            testWorkflowRule.getWorkflowServiceStubs(),
            null);

    // Act
    WorkflowTaskHandler.Result result =
        taskHandler.handleWorkflowTask(HistoryUtils.generateWorkflowTaskWithInitialHistory());
    // Assert
    assertEquals(0, cache.size());
    assertNotNull(result.getTaskCompleted());
    assertFalse(result.getTaskCompleted().hasStickyAttributes());
  }

  @Test
  public void workflowTaskFailOnIncompleteHistory() throws Throwable {
    assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);

    WorkflowExecutorCache cache =
        new WorkflowExecutorCache(10, new WorkflowRunLockManager(), new NoopScope());
    WorkflowServiceStubs client = mock(WorkflowServiceStubs.class);
    when(client.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.newBuilder().build());
    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(client.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);

    // Simulate a stale history node sending a workflow task with an incomplete history
    List<HistoryEvent> history =
        HistoryUtils.generateWorkflowTaskWithInitialHistory().getHistory().getEventsList();
    assertEquals(3, history.size());
    assertEquals(
        EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED, history.get(history.size() - 1).getEventType());
    history = history.subList(0, history.size() - 1);
    when(blockingStub.getWorkflowExecutionHistory(any()))
        .thenReturn(
            GetWorkflowExecutionHistoryResponse.newBuilder()
                .setHistory(History.newBuilder().addAllEvents(history).build())
                .build());

    WorkflowTaskHandler taskHandler =
        new ReplayWorkflowTaskHandler(
            "namespace",
            setUpMockWorkflowFactory(),
            cache,
            SingleWorkerOptions.newBuilder().build(),
            null,
            Duration.ofSeconds(5),
            client,
            null);

    // Send a poll with a partial history and no cached execution so the SDK will request a full
    // history
    WorkflowTaskHandler.Result result =
        taskHandler.handleWorkflowTask(
            HistoryUtils.generateWorkflowTaskWithInitialHistory().toBuilder()
                .setHistory(History.newBuilder().build())
                .setNextPageToken(ByteString.EMPTY)
                .build());

    // Assert
    assertEquals(0, cache.size());
    assertNotNull(result.getTaskFailed());
    assertTrue(result.getTaskFailed().hasFailure());
    assertEquals(
        "Premature end of stream, expectedLastEventID=3 but no more events after eventID=2",
        result.getTaskFailed().getFailure().getMessage());
  }

  @Test
  public void aFailedDownloadIsReportedAsAWorkflowTaskFailure() throws Throwable {
    InMemoryStorageDriver driver = new InMemoryStorageDriver();
    ExternalStorageRunner externalStorage =
        ExternalStorageRunner.create(
            ExternalStorage.newBuilder().setDriver(driver).setPayloadSizeThreshold(0).build());
    PollWorkflowTaskQueueResponse fullTask = HistoryUtils.generateWorkflowTaskWithInitialHistory();
    HistoryEvent startedEvent = fullTask.getHistory().getEvents(0);
    Payload input = Payload.newBuilder().setData(ByteString.copyFromUtf8("input")).build();
    History.Builder storedHistory =
        fullTask.getHistory().toBuilder()
            .setEvents(
                0,
                startedEvent.toBuilder()
                    .setWorkflowExecutionStartedEventAttributes(
                        startedEvent.getWorkflowExecutionStartedEventAttributes().toBuilder()
                            .setInput(Payloads.newBuilder().addPayloads(input))));
    externalStorage.store(storedHistory, null, null, CancellationToken.none());
    driver.failRetrieve = true;

    WorkflowServiceStubs client = mock(WorkflowServiceStubs.class);
    when(client.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.newBuilder().build());

    WorkflowTaskHandler taskHandler =
        new ReplayWorkflowTaskHandler(
            "namespace",
            setUpMockWorkflowFactory(),
            new WorkflowExecutorCache(10, new WorkflowRunLockManager(), new NoopScope()),
            SingleWorkerOptions.newBuilder().setExternalStorageRunner(externalStorage).build(),
            null,
            Duration.ofSeconds(5),
            client,
            null);

    WorkflowTaskHandler.Result result =
        taskHandler.handleWorkflowTask(fullTask.toBuilder().setHistory(storedHistory).build());

    assertNotNull(
        "a failed download must be reported rather than ending the task", result.getTaskFailed());
    assertTrue(result.getTaskFailed().hasFailure());
    assertTrue(
        "the reported failure must say what went wrong, got: "
            + result.getTaskFailed().getFailure().getMessage(),
        result.getTaskFailed().getFailure().getMessage().contains("storage unavailable"));
  }

  @Test
  public void resolvesExternalStorageReferencesInFetchedFullHistory() throws Throwable {
    ExternalStorageRunner externalStorage =
        ExternalStorageRunner.create(
            ExternalStorage.newBuilder()
                .setDriver(new InMemoryStorageDriver())
                .setPayloadSizeThreshold(0)
                .build());
    PollWorkflowTaskQueueResponse fullTask = HistoryUtils.generateWorkflowTaskWithInitialHistory();
    HistoryEvent startedEvent = fullTask.getHistory().getEvents(0);
    Payload input = Payload.newBuilder().setData(ByteString.copyFromUtf8("input")).build();
    History.Builder storedHistory =
        fullTask.getHistory().toBuilder()
            .setEvents(
                0,
                startedEvent.toBuilder()
                    .setWorkflowExecutionStartedEventAttributes(
                        startedEvent.getWorkflowExecutionStartedEventAttributes().toBuilder()
                            .setInput(Payloads.newBuilder().addPayloads(input))));
    externalStorage.store(storedHistory, null, null, CancellationToken.none());

    WorkflowServiceStubs client = mock(WorkflowServiceStubs.class);
    when(client.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.newBuilder().build());
    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(client.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);
    when(blockingStub.getWorkflowExecutionHistory(any()))
        .thenReturn(
            GetWorkflowExecutionHistoryResponse.newBuilder().setHistory(storedHistory).build());

    ReplayWorkflow workflow = mock(ReplayWorkflow.class);
    when(workflow.eventLoop()).thenReturn(true);
    when(workflow.getOutput()).thenReturn(Optional.empty());
    WorkflowContext workflowContext = mock(WorkflowContext.class);
    when(workflowContext.getRunningUpdateHandlers()).thenReturn(new HashMap<>());
    when(workflow.getWorkflowContext()).thenReturn(workflowContext);
    ReplayWorkflowFactory workflowFactory = mock(ReplayWorkflowFactory.class);
    when(workflowFactory.getWorkflow(any(), any())).thenReturn(workflow);
    WorkflowTaskHandler taskHandler =
        new ReplayWorkflowTaskHandler(
            "namespace",
            workflowFactory,
            new WorkflowExecutorCache(10, new WorkflowRunLockManager(), new NoopScope()),
            SingleWorkerOptions.newBuilder().setExternalStorageRunner(externalStorage).build(),
            null,
            Duration.ofSeconds(5),
            client,
            null);

    taskHandler.handleWorkflowTask(
        fullTask.toBuilder().setHistory(History.getDefaultInstance()).build());

    ArgumentCaptor<HistoryEvent> event = ArgumentCaptor.forClass(HistoryEvent.class);
    verify(workflow).start(event.capture(), any());
    assertEquals(
        input,
        event.getValue().getWorkflowExecutionStartedEventAttributes().getInput().getPayloads(0));
  }

  @Test
  public void localActivityMeteringHelper() {
    ReplayWorkflowRunTaskHandler.LocalActivityMeteringHelper laMeteringHelper =
        new ReplayWorkflowRunTaskHandler.LocalActivityMeteringHelper();
    ExecuteLocalActivityParameters executeLA =
        new ExecuteLocalActivityParameters(
            PollActivityTaskQueueResponse.newBuilder().setActivityId("1"),
            null,
            0,
            null,
            false,
            null,
            null);
    laMeteringHelper.addNewLocalActivity(executeLA);
    laMeteringHelper.addNewLocalActivity(
        new ExecuteLocalActivityParameters(
            PollActivityTaskQueueResponse.newBuilder().setActivityId("2"),
            null,
            0,
            null,
            false,
            null,
            null));
    for (int i = 0; i < 5; i++) {
      executeLA.getOnNewAttemptCallback().apply();
    }
    // Verify retries are not counted for the first task
    assertEquals(0, laMeteringHelper.getNonfirstAttempts());
    laMeteringHelper.newWFTStarting();
    assertEquals(0, laMeteringHelper.getNonfirstAttempts());
    // Verify retries are counted for the non first task
    for (int i = 0; i < 5; i++) {
      executeLA.getOnNewAttemptCallback().apply();
    }
    assertEquals(5, laMeteringHelper.getNonfirstAttempts());
  }

  @Test
  public void ifStickyExecutionAttributesAreSetThenWorkflowsAreCached() throws Throwable {
    assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);

    // Arrange
    WorkflowExecutorCache cache =
        new WorkflowExecutorCache(10, new WorkflowRunLockManager(), new NoopScope());
    WorkflowTaskHandler taskHandler =
        new ReplayWorkflowTaskHandler(
            "namespace",
            setUpMockWorkflowFactory(),
            cache,
            SingleWorkerOptions.newBuilder().build(),
            InternalUtils.createStickyTaskQueue("sticky", "taskQueue"),
            Duration.ofSeconds(5),
            testWorkflowRule.getWorkflowServiceStubs(),
            null);

    PollWorkflowTaskQueueResponse workflowTask =
        HistoryUtils.generateWorkflowTaskWithInitialHistory();

    WorkflowTaskHandler.Result result = taskHandler.handleWorkflowTask(workflowTask);

    assertTrue(result.isCompletionCommand());
    assertEquals(0, cache.size()); // do not cache if completion command
    assertNotNull(result.getTaskCompleted());
    StickyExecutionAttributes attributes = result.getTaskCompleted().getStickyAttributes();
    assertEquals("sticky", attributes.getWorkerTaskQueue().getName());
    assertEquals(Durations.fromSeconds(5), attributes.getScheduleToStartTimeout());
  }

  @Test
  public void setsSdkNameAndVersionIfNotSetInHistory() throws Throwable {
    assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);

    WorkflowExecutorCache cache =
        new WorkflowExecutorCache(10, new WorkflowRunLockManager(), new NoopScope());
    WorkflowTaskHandler taskHandler =
        new ReplayWorkflowTaskHandler(
            "namespace",
            setUpMockWorkflowFactory(),
            cache,
            SingleWorkerOptions.newBuilder().build(),
            InternalUtils.createStickyTaskQueue("sticky", "taskQueue"),
            Duration.ofSeconds(5),
            testWorkflowRule.getWorkflowServiceStubs(),
            null);

    PollWorkflowTaskQueueResponse workflowTask =
        HistoryUtils.generateWorkflowTaskWithInitialHistory();

    WorkflowTaskHandler.Result result = taskHandler.handleWorkflowTask(workflowTask);

    assertTrue(result.isCompletionCommand());
    assertEquals(Version.SDK_NAME, result.getTaskCompleted().getSdkMetadata().getSdkName());
    assertEquals(
        Version.LIBRARY_VERSION, result.getTaskCompleted().getSdkMetadata().getSdkVersion());
  }

  private ReplayWorkflowFactory setUpMockWorkflowFactory() throws Throwable {
    ReplayWorkflow mockWorkflow = mock(ReplayWorkflow.class);
    ReplayWorkflowFactory mockFactory = mock(ReplayWorkflowFactory.class);

    when(mockFactory.getWorkflow(any(), any())).thenReturn(mockWorkflow);
    when(mockWorkflow.eventLoop()).thenReturn(true);
    when(mockWorkflow.getOutput()).thenReturn(Optional.empty());

    WorkflowContext mockWorkflowContext = mock(WorkflowContext.class);
    when(mockWorkflowContext.getRunningUpdateHandlers()).thenReturn(new HashMap<>());
    when(mockWorkflowContext.getRunningUpdateHandlers()).thenReturn(new HashMap<>());
    when(mockWorkflow.getWorkflowContext()).thenReturn(mockWorkflowContext);
    return mockFactory;
  }

  private static final class InMemoryStorageDriver implements StorageDriver {
    private final Map<String, Payload> payloads = new HashMap<>();
    private int nextKey;
    boolean failRetrieve;

    @Override
    public String getName() {
      return "test";
    }

    @Override
    public String getType() {
      return "test.in-memory";
    }

    @Override
    public synchronized CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      List<StorageDriverClaim> claims = new ArrayList<>();
      for (Payload payload : payloads) {
        String key = Integer.toString(nextKey++);
        this.payloads.put(key, payload);
        claims.add(new StorageDriverClaim(Collections.singletonMap("key", key)));
      }
      return CompletableFuture.completedFuture(claims);
    }

    @Override
    public synchronized CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      if (failRetrieve) {
        CompletableFuture<List<Payload>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("storage unavailable"));
        return failed;
      }
      List<Payload> retrieved = new ArrayList<>();
      for (StorageDriverClaim claim : claims) {
        retrieved.add(payloads.get(claim.getClaimData().get("key")));
      }
      return CompletableFuture.completedFuture(retrieved);
    }
  }
}
