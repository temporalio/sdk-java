package io.temporal.internal.worker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static junit.framework.TestCase.assertEquals;
import static org.junit.Assert.*;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.common.util.concurrent.Futures;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.CompleteWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.command.v1.SignalExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.StartChildWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.QueryResultType;
import io.temporal.api.failure.v1.ApplicationFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest;
import io.temporal.common.CancellationToken;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.FailureConverter;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.internal.common.InternalUtils;
import io.temporal.internal.concurrent.structured.CancelSource;
import io.temporal.internal.payload.storage.ExternalStorageRunner;
import io.temporal.internal.payload.storage.TestStorageDriver;
import io.temporal.internal.replay.ReplayWorkflow;
import io.temporal.internal.replay.ReplayWorkflowFactory;
import io.temporal.internal.replay.ReplayWorkflowTaskHandler;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testUtils.Eventually;
import io.temporal.testUtils.HistoryUtils;
import io.temporal.worker.MetricsType;
import io.temporal.worker.tuning.FixedSizeSlotSupplier;
import io.temporal.worker.tuning.PollerBehaviorSimpleMaximum;
import io.temporal.worker.tuning.SlotSupplier;
import io.temporal.worker.tuning.WorkflowSlotInfo;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkflowWorkerTest {
  private static final Logger log = LoggerFactory.getLogger(WorkflowWorkerTest.class);
  private final TestStatsReporter reporter = new TestStatsReporter();
  private static final String WORKFLOW_ID = "test-workflow-id";
  private static final String RUN_ID = "test-run-id";
  private static final String WORKFLOW_TYPE = "test-workflow-type";

  @Test
  public void concurrentPollRequestLockTest() throws Exception {
    // Test that if the server sends multiple concurrent workflow tasks for the same workflow the
    // SDK holds the lock during all processing.
    WorkflowServiceStubs client = mock(WorkflowServiceStubs.class);
    when(client.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.newBuilder().build());

    WorkflowRunLockManager runLockManager = new WorkflowRunLockManager();

    Scope metricsScope =
        new RootScopeBuilder()
            .reporter(reporter)
            .reportEvery(com.uber.m3.util.Duration.ofMillis(1));
    SlotSupplier<WorkflowSlotInfo> slotSupplier = new FixedSizeSlotSupplier<>(100);
    WorkflowExecutorCache cache = new WorkflowExecutorCache(10, runLockManager, metricsScope);

    WorkflowTaskHandler taskHandler = mock(WorkflowTaskHandler.class);
    when(taskHandler.isAnyTypeSupported()).thenReturn(true);

    EagerActivityDispatcher eagerActivityDispatcher = mock(EagerActivityDispatcher.class);
    WorkflowWorker worker =
        new WorkflowWorker(
            client,
            "default",
            "task_queue",
            "sticky_task_queue",
            SingleWorkerOptions.newBuilder()
                .setIdentity("test_identity")
                .setBuildId(UUID.randomUUID().toString())
                .setWorkerInstanceKey(UUID.randomUUID().toString())
                .setPollerOptions(
                    PollerOptions.newBuilder()
                        .setPollerBehavior(new PollerBehaviorSimpleMaximum(3))
                        .build())
                .setMetricsScope(metricsScope)
                .build(),
            runLockManager,
            cache,
            taskHandler,
            eagerActivityDispatcher,
            3,
            slotSupplier,
            new NamespaceCapabilities());

    WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub =
        mock(WorkflowServiceGrpc.WorkflowServiceFutureStub.class);
    when(futureStub.shutdownWorker(any(ShutdownWorkerRequest.class)))
        .thenReturn(Futures.immediateFuture(ShutdownWorkerResponse.newBuilder().build()));

    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(client.blockingStub()).thenReturn(blockingStub);
    when(client.futureStub()).thenReturn(futureStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);

    PollWorkflowTaskQueueResponse pollResponse =
        PollWorkflowTaskQueueResponse.newBuilder()
            .setTaskToken(ByteString.copyFrom("token", UTF_8))
            .setWorkflowExecution(
                WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).setRunId(RUN_ID).build())
            .setWorkflowType(WorkflowType.newBuilder().setName(WORKFLOW_TYPE).build())
            .build();

    CountDownLatch blockFirstPollLatch = new CountDownLatch(1);
    CountDownLatch pollTaskQueueLatch = new CountDownLatch(1);
    CountDownLatch blockPollTaskQueueLatch = new CountDownLatch(1);

    when(blockingStub.pollWorkflowTaskQueue(any(PollWorkflowTaskQueueRequest.class)))
        .thenAnswer(
            (Answer<PollWorkflowTaskQueueResponse>)
                invocation -> {
                  blockFirstPollLatch.await();
                  return pollResponse;
                })
        .thenReturn(pollResponse)
        .thenAnswer(
            (Answer<PollWorkflowTaskQueueResponse>)
                invocation -> {
                  pollTaskQueueLatch.countDown();
                  return pollResponse;
                })
        .thenAnswer(
            (Answer<PollWorkflowTaskQueueResponse>)
                invocation -> {
                  blockPollTaskQueueLatch.await();
                  return null;
                });

    CountDownLatch handleTaskLatch = new CountDownLatch(1);
    when(taskHandler.handleWorkflowTask(any(PollWorkflowTaskQueueResponse.class)))
        .thenAnswer(
            (Answer<WorkflowTaskHandler.Result>)
                invocation -> {
                  // Slightly larger than the lock timeout hard coded in WorkflowWorker
                  handleTaskLatch.countDown();
                  Thread.sleep(6000);

                  return new WorkflowTaskHandler.Result(
                      WORKFLOW_TYPE,
                      RespondWorkflowTaskCompletedRequest.newBuilder().build(),
                      null,
                      null,
                      null,
                      false,
                      (id) -> {
                        // verify the lock is still being held
                        assertEquals(1, runLockManager.totalLocks());
                      },
                      null);
                });

    // Mock the server responding to a workflow task complete with another workflow task
    CountDownLatch respondTaskLatch = new CountDownLatch(1);
    when(blockingStub.respondWorkflowTaskCompleted(any(RespondWorkflowTaskCompletedRequest.class)))
        .thenAnswer(
            (Answer<RespondWorkflowTaskCompletedResponse>)
                invocation -> {
                  // verify the lock is still being held
                  assertEquals(1, runLockManager.totalLocks());
                  return RespondWorkflowTaskCompletedResponse.newBuilder()
                      .setWorkflowTask(pollResponse)
                      .build();
                })
        .thenAnswer(
            (Answer<RespondWorkflowTaskCompletedResponse>)
                invocation -> {
                  // verify the lock is still being held
                  assertEquals(1, runLockManager.totalLocks());
                  respondTaskLatch.countDown();
                  return RespondWorkflowTaskCompletedResponse.newBuilder().build();
                });

    assertTrue(worker.start());
    // Unblock the first poll
    blockFirstPollLatch.countDown();
    // Wait until we have got all the polls
    pollTaskQueueLatch.await();
    // Wait until the worker handles at least one WFT
    handleTaskLatch.await();
    // Verify 3 slots have been used
    Eventually.assertEventually(
        Duration.ofSeconds(10),
        () -> {
          // Since all polls have the same runID only one should get through, the other two should
          // be
          // blocked
          assertEquals(1, runLockManager.totalLocks());
          reporter.assertGauge(
              MetricsType.WORKER_TASK_SLOTS_AVAILABLE,
              ImmutableMap.of("worker_type", "WorkflowWorker"),
              97.0);
        });
    // Wait for the worker to respond, by this time the other blocked tasks should have timed out
    respondTaskLatch.await();
    // All slots should be available
    Eventually.assertEventually(
        Duration.ofSeconds(10),
        () -> {
          // No task should have the lock anymore
          assertEquals(0, runLockManager.totalLocks());
          reporter.assertGauge(
              MetricsType.WORKER_TASK_SLOTS_AVAILABLE,
              ImmutableMap.of("worker_type", "WorkflowWorker"),
              100.0);
        });
    // Cleanup
    worker.shutdown(new ShutdownManager(), false).get();
    // Verify we only handled two tasks
    verify(taskHandler, times(2)).handleWorkflowTask(any());
  }

  @Test
  public void respondWorkflowTaskFailureMetricTest() throws Exception {
    // Test that if the SDK gets a failure on RespondWorkflowTaskCompleted it does not increment
    // workflow_task_execution_failed.
    WorkflowServiceStubs client = mock(WorkflowServiceStubs.class);
    when(client.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.newBuilder().build());

    WorkflowRunLockManager runLockManager = new WorkflowRunLockManager();

    Scope metricsScope =
        new RootScopeBuilder()
            .reporter(reporter)
            .reportEvery(com.uber.m3.util.Duration.ofMillis(1));
    WorkflowExecutorCache cache = new WorkflowExecutorCache(10, runLockManager, metricsScope);
    SlotSupplier<WorkflowSlotInfo> slotSupplier = new FixedSizeSlotSupplier<>(10);

    WorkflowTaskHandler taskHandler = mock(WorkflowTaskHandler.class);
    when(taskHandler.isAnyTypeSupported()).thenReturn(true);

    EagerActivityDispatcher eagerActivityDispatcher = mock(EagerActivityDispatcher.class);
    WorkflowWorker worker =
        new WorkflowWorker(
            client,
            "default",
            "task_queue",
            "sticky_task_queue",
            SingleWorkerOptions.newBuilder()
                .setIdentity("test_identity")
                .setBuildId(UUID.randomUUID().toString())
                .setWorkerInstanceKey(UUID.randomUUID().toString())
                .setPollerOptions(
                    PollerOptions.newBuilder()
                        .setPollerBehavior(new PollerBehaviorSimpleMaximum(1))
                        .build())
                .setMetricsScope(metricsScope)
                .build(),
            runLockManager,
            cache,
            taskHandler,
            eagerActivityDispatcher,
            3,
            slotSupplier,
            new NamespaceCapabilities());

    WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub =
        mock(WorkflowServiceGrpc.WorkflowServiceFutureStub.class);
    when(futureStub.shutdownWorker(any(ShutdownWorkerRequest.class)))
        .thenReturn(Futures.immediateFuture(ShutdownWorkerResponse.newBuilder().build()));

    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(client.blockingStub()).thenReturn(blockingStub);
    when(client.futureStub()).thenReturn(futureStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);

    PollWorkflowTaskQueueResponse pollResponse =
        PollWorkflowTaskQueueResponse.newBuilder()
            .setTaskToken(ByteString.copyFrom("token", UTF_8))
            .setWorkflowExecution(
                WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).setRunId(RUN_ID).build())
            .setWorkflowType(WorkflowType.newBuilder().setName(WORKFLOW_TYPE).build())
            .build();

    CountDownLatch pollTaskQueueLatch = new CountDownLatch(1);
    CountDownLatch blockPollTaskQueueLatch = new CountDownLatch(1);

    when(blockingStub.pollWorkflowTaskQueue(any(PollWorkflowTaskQueueRequest.class)))
        .thenReturn(pollResponse)
        .thenAnswer(
            (Answer<PollWorkflowTaskQueueResponse>)
                invocation -> {
                  pollTaskQueueLatch.countDown();
                  blockPollTaskQueueLatch.await();
                  return null;
                });
    ;

    CountDownLatch handleTaskLatch = new CountDownLatch(1);

    when(taskHandler.handleWorkflowTask(any(PollWorkflowTaskQueueResponse.class)))
        .thenAnswer(
            (Answer<WorkflowTaskHandler.Result>)
                invocation -> {
                  handleTaskLatch.countDown();

                  return new WorkflowTaskHandler.Result(
                      WORKFLOW_TYPE,
                      RespondWorkflowTaskCompletedRequest.newBuilder().build(),
                      null,
                      null,
                      null,
                      false,
                      null,
                      null);
                });

    when(blockingStub.respondWorkflowTaskCompleted(any(RespondWorkflowTaskCompletedRequest.class)))
        .thenThrow(new RuntimeException());

    assertTrue(worker.start());
    // Wait until we have got all the polls
    pollTaskQueueLatch.await();
    // Wait until the worker handles at least one WFT
    handleTaskLatch.await();
    // Cleanup
    worker.shutdown(new ShutdownManager(), false).get();
    // Make sure we don't report workflow task failure
    reporter.assertNoMetric(
        MetricsType.WORKFLOW_TASK_EXECUTION_FAILURE_COUNTER,
        ImmutableMap.of("worker_type", "WorkflowWorker", "workflow_type", "test-workflow-type"));
  }

  @Test
  public void resetWorkflowIdFromWorkflowTaskTest() throws Throwable {
    WorkflowServiceStubs client = mock(WorkflowServiceStubs.class);
    when(client.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.newBuilder().build());

    WorkflowRunLockManager runLockManager = new WorkflowRunLockManager();

    Scope metricScope = new NoopScope();
    WorkflowExecutorCache cache = new WorkflowExecutorCache(1, runLockManager, metricScope);

    SlotSupplier<WorkflowSlotInfo> slotSupplier = new FixedSizeSlotSupplier<>(1);

    WorkflowTaskHandler rootTaskHandler =
        new ReplayWorkflowTaskHandler(
            "namespace",
            setUpMockWorkflowFactory(),
            cache,
            SingleWorkerOptions.newBuilder().build(),
            InternalUtils.createStickyTaskQueue("sticky", "taskQueue"),
            Duration.ofSeconds(5),
            client,
            null);
    // Queue to pass the reset event id from WorkflowTaskHandler to the test
    BlockingQueue<Long> resetEventIdQueue = new ArrayBlockingQueue<>(1);
    // Wrap the root task handler to capture the reset event id
    WorkflowTaskHandler taskHandler =
        new WorkflowTaskHandler() {
          @Override
          public WorkflowTaskHandler.Result handleWorkflowTask(PollWorkflowTaskQueueResponse task)
              throws Exception {
            WorkflowTaskHandler.Result result = rootTaskHandler.handleWorkflowTask(task);
            return new WorkflowTaskHandler.Result(
                result.getWorkflowType(),
                result.getTaskCompleted(),
                result.getTaskFailed(),
                result.getQueryCompleted(),
                result.getRequestRetryOptions(),
                result.isCompletionCommand(),
                (id) -> {
                  resetEventIdQueue.add(id);
                  result.getResetEventIdHandle().apply(id);
                },
                null);
          }

          @Override
          public boolean isAnyTypeSupported() {
            return rootTaskHandler.isAnyTypeSupported();
          }
        };

    EagerActivityDispatcher eagerActivityDispatcher = mock(EagerActivityDispatcher.class);
    WorkflowWorker worker =
        new WorkflowWorker(
            client,
            "default",
            "taskQueue",
            "sticky",
            SingleWorkerOptions.newBuilder()
                .setIdentity("test_identity")
                .setBuildId(UUID.randomUUID().toString())
                .setWorkerInstanceKey(UUID.randomUUID().toString())
                .setPollerOptions(
                    PollerOptions.newBuilder()
                        .setPollerBehavior(new PollerBehaviorSimpleMaximum(1))
                        .build())
                .setMetricsScope(metricScope)
                .build(),
            runLockManager,
            cache,
            taskHandler,
            eagerActivityDispatcher,
            3,
            slotSupplier,
            new NamespaceCapabilities());

    WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub =
        mock(WorkflowServiceGrpc.WorkflowServiceFutureStub.class);
    when(futureStub.shutdownWorker(any(ShutdownWorkerRequest.class)))
        .thenReturn(Futures.immediateFuture(ShutdownWorkerResponse.newBuilder().build()));

    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(client.blockingStub()).thenReturn(blockingStub);
    when(client.futureStub()).thenReturn(futureStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);

    PollWorkflowTaskQueueResponse pollResponse =
        PollWorkflowTaskQueueResponse.newBuilder()
            .setHistory(HistoryUtils.generateWorkflowTaskWithInitialHistory().getHistory())
            .setTaskToken(ByteString.copyFrom("token", UTF_8))
            .setWorkflowExecution(
                WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).setRunId(RUN_ID).build())
            .setWorkflowType(WorkflowType.newBuilder().setName(WORKFLOW_TYPE).build())
            .build();

    when(blockingStub.pollWorkflowTaskQueue(any(PollWorkflowTaskQueueRequest.class)))
        .thenReturn(pollResponse);
    RespondWorkflowTaskCompletedResponse workflowTaskResponse =
        RespondWorkflowTaskCompletedResponse.newBuilder().setResetHistoryEventId(1).build();
    when(blockingStub.respondWorkflowTaskCompleted(any(RespondWorkflowTaskCompletedRequest.class)))
        .thenReturn(workflowTaskResponse);

    assertTrue(worker.start());
    // Assert that the reset event id is received by WorkflowTaskHandler
    assertEquals(Long.valueOf(1), resetEventIdQueue.take());
    // Cleanup
    worker.shutdown(new ShutdownManager(), true).get();
  }

  private ReplayWorkflowFactory setUpMockWorkflowFactory() throws Throwable {
    ReplayWorkflow mockWorkflow = mock(ReplayWorkflow.class);
    ReplayWorkflowFactory mockFactory = mock(ReplayWorkflowFactory.class);

    when(mockFactory.getWorkflow(any(), any())).thenReturn(mockWorkflow);
    when(mockFactory.isAnyTypeSupported()).thenReturn(true);
    when(mockWorkflow.eventLoop()).thenReturn(false);
    return mockFactory;
  }

  @Test
  public void aTaskAbandonedWhileShuttingDownIsNotReported() throws Exception {
    WorkflowServiceStubs client = mock(WorkflowServiceStubs.class);
    when(client.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.newBuilder().build());
    WorkflowRunLockManager runLockManager = new WorkflowRunLockManager();
    Scope metricsScope =
        new RootScopeBuilder()
            .reporter(reporter)
            .reportEvery(com.uber.m3.util.Duration.ofMillis(1));
    WorkflowExecutorCache cache = new WorkflowExecutorCache(10, runLockManager, metricsScope);
    WorkflowTaskHandler taskHandler = mock(WorkflowTaskHandler.class);
    when(taskHandler.isAnyTypeSupported()).thenReturn(true);

    CountDownLatch handlerEntered = new CountDownLatch(1);
    CountDownLatch releaseHandler = new CountDownLatch(1);
    CountDownLatch escaped = new CountDownLatch(1);
    CancelSource<CancellationException> storageCancellation =
        new CancelSource<>(() -> new CancellationException("Worker shutdown"));

    WorkflowWorker worker =
        new WorkflowWorker(
            client,
            "default",
            "task_queue",
            "sticky_task_queue",
            SingleWorkerOptions.newBuilder()
                .setIdentity("test_identity")
                .setBuildId(UUID.randomUUID().toString())
                .setWorkerInstanceKey(UUID.randomUUID().toString())
                .setPollerOptions(
                    PollerOptions.newBuilder()
                        .setPollerBehavior(new PollerBehaviorSimpleMaximum(1))
                        .setUncaughtExceptionHandler((thread, error) -> escaped.countDown())
                        .build())
                .setMetricsScope(metricsScope)
                .setStorageCancellation(storageCancellation.token())
                .build(),
            runLockManager,
            cache,
            taskHandler,
            mock(EagerActivityDispatcher.class),
            3,
            new FixedSizeSlotSupplier<>(10),
            new NamespaceCapabilities());

    WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub =
        mock(WorkflowServiceGrpc.WorkflowServiceFutureStub.class);
    when(futureStub.shutdownWorker(any(ShutdownWorkerRequest.class)))
        .thenReturn(Futures.immediateFuture(ShutdownWorkerResponse.newBuilder().build()));
    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(client.blockingStub()).thenReturn(blockingStub);
    when(client.futureStub()).thenReturn(futureStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);

    PollWorkflowTaskQueueResponse pollResponse =
        PollWorkflowTaskQueueResponse.newBuilder()
            .setTaskToken(ByteString.copyFrom("token", UTF_8))
            .setWorkflowExecution(
                WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).setRunId(RUN_ID).build())
            .setWorkflowType(WorkflowType.newBuilder().setName(WORKFLOW_TYPE).build())
            .build();
    CountDownLatch blockPolls = new CountDownLatch(1);
    when(blockingStub.pollWorkflowTaskQueue(any(PollWorkflowTaskQueueRequest.class)))
        .thenReturn(pollResponse)
        .thenAnswer(
            (Answer<PollWorkflowTaskQueueResponse>)
                invocation -> {
                  blockPolls.await();
                  return null;
                });

    // The task is abandoned part way through, which is what stopping storage looks like.
    when(taskHandler.handleWorkflowTask(any(PollWorkflowTaskQueueResponse.class)))
        .thenAnswer(
            (Answer<WorkflowTaskHandler.Result>)
                invocation -> {
                  handlerEntered.countDown();
                  try {
                    releaseHandler.await(10, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  throw new CancellationException("Worker shutdown");
                });

    assertTrue(worker.start());
    assertTrue(handlerEntered.await(10, TimeUnit.SECONDS));
    storageCancellation.cancel();
    CompletableFuture<Void> shutdown = worker.shutdown(new ShutdownManager(), true);
    releaseHandler.countDown();

    assertFalse(
        "abandoning a task while shutting down must not surface as an error",
        escaped.await(2, TimeUnit.SECONDS));
    verify(blockingStub, never())
        .respondWorkflowTaskFailed(any(RespondWorkflowTaskFailedRequest.class));
    assertEquals(
        "a task abandoned while shutting down must not count as a failed task",
        0,
        worker.getTaskCounter().getTotalFailed());
    shutdown.get();
  }

  @Test
  public void storageBreakingDuringAForcedShutdownIsStillReported() throws Exception {
    // Cancelling storage means we abandoned the work. Storage genuinely breaking at the same
    // moment is a different thing and must not disappear with it.
    TestStorageDriver driver = TestStorageDriver.create().failStores(1);
    Payload result = Payload.newBuilder().setData(ByteString.copyFrom("result", UTF_8)).build();
    RespondWorkflowTaskCompletedRequest taskCompleted =
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .addCommands(
                Command.newBuilder()
                    .setCompleteWorkflowExecutionCommandAttributes(
                        CompleteWorkflowExecutionCommandAttributes.newBuilder()
                            .setResult(Payloads.newBuilder().addPayloads(result))))
            .build();
    CancelSource<CancellationException> storageCancellation =
        new CancelSource<>(() -> new CancellationException("Worker shutdown"));
    storageCancellation.cancel();

    runOneTask(
        driver,
        new WorkflowTaskHandler.Result(
            WORKFLOW_TYPE, taskCompleted, null, null, null, false, null, null),
        storageCancellation.token(),
        blockingStub ->
            verify(blockingStub)
                .respondWorkflowTaskFailed(any(RespondWorkflowTaskFailedRequest.class)));

    assertEquals("expected one injected store failure", 1, driver.injectedFailures.get());
  }

  @Test
  public void payloadsInAFailedWorkflowTaskAreOffloaded() throws Exception {
    TestStorageDriver driver = TestStorageDriver.create();
    Payload details = Payload.newBuilder().setData(ByteString.copyFrom("details", UTF_8)).build();
    RespondWorkflowTaskFailedRequest taskFailed =
        RespondWorkflowTaskFailedRequest.newBuilder()
            .setFailure(
                Failure.newBuilder()
                    .setMessage("boom")
                    .setApplicationFailureInfo(
                        ApplicationFailureInfo.newBuilder()
                            .setDetails(Payloads.newBuilder().addPayloads(details))))
            .build();

    ArgumentCaptor<RespondWorkflowTaskFailedRequest> sent =
        ArgumentCaptor.forClass(RespondWorkflowTaskFailedRequest.class);
    runOneTask(
        driver,
        new WorkflowTaskHandler.Result(
            WORKFLOW_TYPE, null, taskFailed, null, null, false, null, null),
        blockingStub -> verify(blockingStub).respondWorkflowTaskFailed(sent.capture()));

    assertEquals("the failure details must be offloaded", 1, driver.storedCount());
    assertNotEquals(
        "the failure details must be replaced by a reference",
        details,
        sent.getValue().getFailure().getApplicationFailureInfo().getDetails().getPayloads(0));
  }

  @Test
  public void payloadsInADirectQueryResponseAreOffloaded() throws Exception {
    TestStorageDriver driver = TestStorageDriver.create();
    Payload answer = Payload.newBuilder().setData(ByteString.copyFrom("answer", UTF_8)).build();
    RespondQueryTaskCompletedRequest queryCompleted =
        RespondQueryTaskCompletedRequest.newBuilder()
            .setQueryResult(Payloads.newBuilder().addPayloads(answer))
            .build();

    ArgumentCaptor<RespondQueryTaskCompletedRequest> sent =
        ArgumentCaptor.forClass(RespondQueryTaskCompletedRequest.class);
    runOneTask(
        driver,
        new WorkflowTaskHandler.Result(
            WORKFLOW_TYPE, null, null, queryCompleted, null, false, null, null),
        blockingStub -> verify(blockingStub).respondQueryTaskCompleted(sent.capture()));

    assertEquals("the query answer must be offloaded", 1, driver.storedCount());
    assertNotEquals(
        "the query answer must be replaced by a reference",
        answer,
        sent.getValue().getQueryResult().getPayloads(0));
  }

  @Test
  public void theWorkflowTaskFailureReportingAStorageFailureIsNotOffloaded() throws Exception {
    TestStorageDriver driver = TestStorageDriver.create().failStores(10);
    Payload result = Payload.newBuilder().setData(ByteString.copyFrom("result", UTF_8)).build();
    RespondWorkflowTaskCompletedRequest taskCompleted =
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .addCommands(
                Command.newBuilder()
                    .setCompleteWorkflowExecutionCommandAttributes(
                        CompleteWorkflowExecutionCommandAttributes.newBuilder()
                            .setResult(Payloads.newBuilder().addPayloads(result))))
            .build();

    ArgumentCaptor<RespondWorkflowTaskFailedRequest> sent =
        ArgumentCaptor.forClass(RespondWorkflowTaskFailedRequest.class);
    runOneTask(
        driver,
        new WorkflowTaskHandler.Result(
            WORKFLOW_TYPE, taskCompleted, null, null, null, false, null, null),
        CancellationToken.none(),
        converterAttachingDetailsToFailures(),
        blockingStub -> verify(blockingStub).respondWorkflowTaskFailed(sent.capture()));

    assertEquals(
        "only the original completion may reach storage", 1, driver.injectedFailures.get());
    assertEquals(
        "the failure details must reach the server untouched",
        FAILURE_DETAIL,
        sent.getValue().getFailure().getApplicationFailureInfo().getDetails().getPayloads(0));
  }

  @Test
  public void theQueryResponseReportingAStorageFailureIsNotOffloaded() throws Exception {
    TestStorageDriver driver = TestStorageDriver.create().failStores(10);
    Payload answer = Payload.newBuilder().setData(ByteString.copyFrom("answer", UTF_8)).build();
    RespondQueryTaskCompletedRequest queryCompleted =
        RespondQueryTaskCompletedRequest.newBuilder()
            .setQueryResult(Payloads.newBuilder().addPayloads(answer))
            .build();

    ArgumentCaptor<RespondQueryTaskCompletedRequest> sent =
        ArgumentCaptor.forClass(RespondQueryTaskCompletedRequest.class);
    runOneTask(
        driver,
        new WorkflowTaskHandler.Result(
            WORKFLOW_TYPE, null, null, queryCompleted, null, false, null, null),
        CancellationToken.none(),
        converterAttachingDetailsToFailures(),
        blockingStub -> verify(blockingStub).respondQueryTaskCompleted(sent.capture()));

    assertEquals("only the original answer may reach storage", 1, driver.injectedFailures.get());
    assertEquals(QueryResultType.QUERY_RESULT_TYPE_FAILED, sent.getValue().getCompletedType());
    assertEquals(
        "the failure details must reach the server untouched",
        FAILURE_DETAIL,
        sent.getValue().getFailure().getApplicationFailureInfo().getDetails().getPayloads(0));
  }

  private static final Payload FAILURE_DETAIL =
      Payload.newBuilder().setData(ByteString.copyFrom("failure-detail", UTF_8)).build();

  /**
   * The default failure converter produces failures with no payloads, so a response reporting a
   * storage failure would have nothing to offload. This one gives it something.
   */
  private static DataConverter converterAttachingDetailsToFailures() {
    return DefaultDataConverter.newDefaultInstance()
        .withFailureConverter(
            new FailureConverter() {
              @Nonnull
              @Override
              public RuntimeException failureToException(
                  @Nonnull Failure failure, @Nonnull DataConverter dataConverter) {
                throw new UnsupportedOperationException();
              }

              @Nonnull
              @Override
              public Failure exceptionToFailure(
                  @Nonnull Throwable throwable, @Nonnull DataConverter dataConverter) {
                return Failure.newBuilder()
                    .setMessage(throwable.getMessage())
                    .setApplicationFailureInfo(
                        ApplicationFailureInfo.newBuilder()
                            .setDetails(Payloads.newBuilder().addPayloads(FAILURE_DETAIL)))
                    .build();
              }
            });
  }

  /** Runs a single workflow task through a worker wired to {@code driver}, then verifies. */
  private void runOneTask(
      TestStorageDriver driver,
      WorkflowTaskHandler.Result handlerResult,
      java.util.function.Consumer<WorkflowServiceGrpc.WorkflowServiceBlockingStub> verification)
      throws Exception {
    runOneTask(driver, handlerResult, CancellationToken.none(), verification);
  }

  private void runOneTask(
      TestStorageDriver driver,
      WorkflowTaskHandler.Result handlerResult,
      CancellationToken<CancellationException> storageCancellation,
      java.util.function.Consumer<WorkflowServiceGrpc.WorkflowServiceBlockingStub> verification)
      throws Exception {
    runOneTask(
        driver,
        handlerResult,
        storageCancellation,
        DefaultDataConverter.newDefaultInstance(),
        verification);
  }

  private void runOneTask(
      TestStorageDriver driver,
      WorkflowTaskHandler.Result handlerResult,
      CancellationToken<CancellationException> storageCancellation,
      DataConverter dataConverter,
      java.util.function.Consumer<WorkflowServiceGrpc.WorkflowServiceBlockingStub> verification)
      throws Exception {
    WorkflowServiceStubs client = mock(WorkflowServiceStubs.class);
    when(client.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.newBuilder().build());
    WorkflowRunLockManager runLockManager = new WorkflowRunLockManager();
    Scope metricsScope =
        new RootScopeBuilder()
            .reporter(reporter)
            .reportEvery(com.uber.m3.util.Duration.ofMillis(1));
    WorkflowExecutorCache cache = new WorkflowExecutorCache(10, runLockManager, metricsScope);
    WorkflowTaskHandler taskHandler = mock(WorkflowTaskHandler.class);
    when(taskHandler.isAnyTypeSupported()).thenReturn(true);

    WorkflowWorker worker =
        new WorkflowWorker(
            client,
            "default",
            "task_queue",
            "sticky_task_queue",
            SingleWorkerOptions.newBuilder()
                .setIdentity("test_identity")
                .setBuildId(UUID.randomUUID().toString())
                .setWorkerInstanceKey(UUID.randomUUID().toString())
                .setPollerOptions(
                    PollerOptions.newBuilder()
                        .setPollerBehavior(new PollerBehaviorSimpleMaximum(1))
                        .build())
                .setMetricsScope(metricsScope)
                .setExternalStorageRunner(
                    ExternalStorageRunner.create(
                        ExternalStorage.newBuilder()
                            .setDriver(driver)
                            .setPayloadSizeThreshold(0)
                            .build()))
                .setStorageCancellation(storageCancellation)
                .setDataConverter(dataConverter)
                .build(),
            runLockManager,
            cache,
            taskHandler,
            mock(EagerActivityDispatcher.class),
            3,
            new FixedSizeSlotSupplier<>(10),
            new NamespaceCapabilities());

    WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub =
        mock(WorkflowServiceGrpc.WorkflowServiceFutureStub.class);
    when(futureStub.shutdownWorker(any(ShutdownWorkerRequest.class)))
        .thenReturn(Futures.immediateFuture(ShutdownWorkerResponse.newBuilder().build()));
    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(client.blockingStub()).thenReturn(blockingStub);
    when(client.futureStub()).thenReturn(futureStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);

    PollWorkflowTaskQueueResponse pollResponse =
        PollWorkflowTaskQueueResponse.newBuilder()
            .setTaskToken(ByteString.copyFrom("token", UTF_8))
            .setWorkflowExecution(
                WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).setRunId(RUN_ID).build())
            .setWorkflowType(WorkflowType.newBuilder().setName(WORKFLOW_TYPE).build())
            .build();
    CountDownLatch blockPolls = new CountDownLatch(1);
    when(blockingStub.pollWorkflowTaskQueue(any(PollWorkflowTaskQueueRequest.class)))
        .thenReturn(pollResponse)
        .thenAnswer(
            (Answer<PollWorkflowTaskQueueResponse>)
                invocation -> {
                  blockPolls.await();
                  return null;
                });

    CountDownLatch handled = new CountDownLatch(1);
    when(taskHandler.handleWorkflowTask(any(PollWorkflowTaskQueueResponse.class)))
        .thenAnswer(
            (Answer<WorkflowTaskHandler.Result>)
                invocation -> {
                  handled.countDown();
                  return handlerResult;
                });

    assertTrue(worker.start());
    assertTrue(handled.await(10, TimeUnit.SECONDS));
    worker.shutdown(new ShutdownManager(), false).get();
    verification.accept(blockingStub);
  }

  @Test
  public void aStoreThatFailsWhileShuttingDownIsNotTreatedAsAProblem() throws Exception {
    LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.setContext(loggerContext);
    logs.start();
    ch.qos.logback.classic.Logger workerLog =
        loggerContext.getLogger(WorkflowWorker.class.getName());
    workerLog.addAppender(logs);
    try {
      WorkflowServiceStubs client = mock(WorkflowServiceStubs.class);
      when(client.getServerCapabilities())
          .thenReturn(() -> GetSystemInfoResponse.Capabilities.newBuilder().build());

      WorkflowRunLockManager runLockManager = new WorkflowRunLockManager();
      Scope metricsScope =
          new RootScopeBuilder()
              .reporter(reporter)
              .reportEvery(com.uber.m3.util.Duration.ofMillis(1));
      WorkflowExecutorCache cache = new WorkflowExecutorCache(10, runLockManager, metricsScope);
      SlotSupplier<WorkflowSlotInfo> slotSupplier = new FixedSizeSlotSupplier<>(10);

      WorkflowTaskHandler taskHandler = mock(WorkflowTaskHandler.class);
      when(taskHandler.isAnyTypeSupported()).thenReturn(true);

      CountDownLatch storeEntered = new CountDownLatch(1);
      CountDownLatch releaseStore = new CountDownLatch(1);
      TestStorageDriver driver =
          TestStorageDriver.create().blockStores(storeEntered, releaseStore).cancelStores(1);
      CountDownLatch escaped = new CountDownLatch(1);
      CancelSource<CancellationException> storageCancellation =
          new CancelSource<>(() -> new CancellationException("Worker shutdown"));

      WorkflowWorker worker =
          new WorkflowWorker(
              client,
              "default",
              "task_queue",
              "sticky_task_queue",
              SingleWorkerOptions.newBuilder()
                  .setIdentity("test_identity")
                  .setBuildId(UUID.randomUUID().toString())
                  .setWorkerInstanceKey(UUID.randomUUID().toString())
                  .setPollerOptions(
                      PollerOptions.newBuilder()
                          .setPollerBehavior(new PollerBehaviorSimpleMaximum(1))
                          .setUncaughtExceptionHandler((thread, error) -> escaped.countDown())
                          .build())
                  .setMetricsScope(metricsScope)
                  .setExternalStorageRunner(
                      ExternalStorageRunner.create(
                          ExternalStorage.newBuilder()
                              .setDriver(driver)
                              .setPayloadSizeThreshold(0)
                              .build()))
                  .setStorageCancellation(storageCancellation.token())
                  .build(),
              runLockManager,
              cache,
              taskHandler,
              mock(EagerActivityDispatcher.class),
              3,
              slotSupplier,
              new NamespaceCapabilities());

      WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub =
          mock(WorkflowServiceGrpc.WorkflowServiceFutureStub.class);
      when(futureStub.shutdownWorker(any(ShutdownWorkerRequest.class)))
          .thenReturn(Futures.immediateFuture(ShutdownWorkerResponse.newBuilder().build()));
      WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
          mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
      when(client.blockingStub()).thenReturn(blockingStub);
      when(client.futureStub()).thenReturn(futureStub);
      when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);

      PollWorkflowTaskQueueResponse pollResponse =
          PollWorkflowTaskQueueResponse.newBuilder()
              .setTaskToken(ByteString.copyFrom("token", UTF_8))
              .setWorkflowExecution(
                  WorkflowExecution.newBuilder()
                      .setWorkflowId(WORKFLOW_ID)
                      .setRunId(RUN_ID)
                      .build())
              .setWorkflowType(WorkflowType.newBuilder().setName(WORKFLOW_TYPE).build())
              .build();
      CountDownLatch pollTaskQueueLatch = new CountDownLatch(1);
      CountDownLatch blockPollTaskQueueLatch = new CountDownLatch(1);
      when(blockingStub.pollWorkflowTaskQueue(any(PollWorkflowTaskQueueRequest.class)))
          .thenReturn(pollResponse)
          .thenAnswer(
              (Answer<PollWorkflowTaskQueueResponse>)
                  invocation -> {
                    pollTaskQueueLatch.countDown();
                    blockPollTaskQueueLatch.await();
                    return null;
                  });

      when(taskHandler.handleWorkflowTask(any(PollWorkflowTaskQueueResponse.class)))
          .thenAnswer(
              (Answer<WorkflowTaskHandler.Result>)
                  invocation ->
                      new WorkflowTaskHandler.Result(
                          WORKFLOW_TYPE,
                          RespondWorkflowTaskCompletedRequest.newBuilder()
                              .addCommands(
                                  Command.newBuilder()
                                      .setCompleteWorkflowExecutionCommandAttributes(
                                          CompleteWorkflowExecutionCommandAttributes.newBuilder()
                                              .setResult(
                                                  Payloads.newBuilder()
                                                      .addPayloads(
                                                          Payload.newBuilder()
                                                              .setData(
                                                                  ByteString.copyFrom(
                                                                      "result", UTF_8))))))
                              .build(),
                          null,
                          null,
                          null,
                          false,
                          null,
                          null));

      assertTrue(worker.start());
      assertTrue(storeEntered.await(10, TimeUnit.SECONDS));

      CompletableFuture<Void> shutdown = worker.shutdown(new ShutdownManager(), true);
      storageCancellation.cancel();
      releaseStore.countDown();

      assertFalse(
          "a store that fails while shutting down must not surface as an error on the task",
          escaped.await(2, TimeUnit.SECONDS));
      verify(blockingStub, never())
          .respondWorkflowTaskFailed(any(RespondWorkflowTaskFailedRequest.class));
      shutdown.get();

      assertFalse(
          "shutting down must not be logged as a failure to report progress",
          logs.list.stream()
              .anyMatch(
                  event ->
                      event.getLevel() == Level.WARN
                          && event.getMessage().contains("Failure while reporting")));
    } finally {
      workerLog.detachAppender(logs);
      logs.stop();
    }
  }

  /** One driver for these tests: stores in memory, and can block or fail on demand. */
  @Test
  public void deriveStorageTargetPointsACompletionAtItsParent() {
    StorageDriverTargetInfo child = new StorageDriverWorkflowInfo("ns", "child", "run-1", "Child");
    StorageDriverTargetInfo parent =
        new StorageDriverWorkflowInfo("ns", "parent", "parent-run", null);
    Command command =
        Command.newBuilder()
            .setCompleteWorkflowExecutionCommandAttributes(
                CompleteWorkflowExecutionCommandAttributes.newBuilder())
            .build();

    assertEquals(parent, WorkflowWorker.deriveStorageTarget("ns", child, command, parent));
  }

  @Test
  public void deriveStorageTargetKeepsACompletionOnItselfWithoutAParent() {
    StorageDriverTargetInfo self =
        new StorageDriverWorkflowInfo("ns", "wf-1", "run-1", "MyWorkflow");
    Command command =
        Command.newBuilder()
            .setCompleteWorkflowExecutionCommandAttributes(
                CompleteWorkflowExecutionCommandAttributes.newBuilder())
            .build();

    assertEquals(self, WorkflowWorker.deriveStorageTarget("ns", self, command, null));
  }

  @Test
  public void deriveStorageTargetKeepsActivityCommandsOnTheWorkflow() {
    StorageDriverTargetInfo workflowDefault =
        new StorageDriverWorkflowInfo("ns", "wf-1", "run-1", "MyWorkflow");
    Command command =
        Command.newBuilder()
            .setScheduleActivityTaskCommandAttributes(
                ScheduleActivityTaskCommandAttributes.newBuilder()
                    .setActivityId("act-1")
                    .setActivityType(ActivityType.newBuilder().setName("MyActivity")))
            .build();

    assertEquals(
        workflowDefault, WorkflowWorker.deriveStorageTarget("ns", workflowDefault, command));
  }

  @Test
  public void deriveStorageTargetPointsChildWorkflowCommandsAtTheChild() {
    StorageDriverTargetInfo parent =
        new StorageDriverWorkflowInfo("ns", "parent", "parent-run", "Parent");
    Command command =
        Command.newBuilder()
            .setStartChildWorkflowExecutionCommandAttributes(
                StartChildWorkflowExecutionCommandAttributes.newBuilder()
                    .setWorkflowId("child-1")
                    .setWorkflowType(WorkflowType.newBuilder().setName("Child")))
            .build();

    assertEquals(
        new StorageDriverWorkflowInfo("ns", "child-1", null, "Child"),
        WorkflowWorker.deriveStorageTarget("ns", parent, command));
  }

  @Test
  public void deriveStorageTargetPointsSignalCommandsAtTheTargetWorkflow() {
    StorageDriverTargetInfo self = new StorageDriverWorkflowInfo("ns", "self", "self-run", "Self");
    Command command =
        Command.newBuilder()
            .setSignalExternalWorkflowExecutionCommandAttributes(
                SignalExternalWorkflowExecutionCommandAttributes.newBuilder()
                    .setExecution(
                        WorkflowExecution.newBuilder()
                            .setWorkflowId("other")
                            .setRunId("other-run")))
            .build();

    assertEquals(
        new StorageDriverWorkflowInfo("ns", "other", "other-run", null),
        WorkflowWorker.deriveStorageTarget("ns", self, command));
  }

  @Test
  public void deriveStorageTargetPointsContinueAsNewAtTheNewRun() {
    StorageDriverTargetInfo current =
        new StorageDriverWorkflowInfo("ns", "wf-1", "run-1", "CurrentWorkflow");
    Command command =
        Command.newBuilder()
            .setContinueAsNewWorkflowExecutionCommandAttributes(
                ContinueAsNewWorkflowExecutionCommandAttributes.newBuilder()
                    .setWorkflowType(WorkflowType.newBuilder().setName("NextWorkflow")))
            .build();

    assertEquals(
        new StorageDriverWorkflowInfo("ns", "wf-1", null, "NextWorkflow"),
        WorkflowWorker.deriveStorageTarget("ns", current, command));
  }

  @Test
  public void deriveStorageTargetKeepsWorkflowTypeForContinueAsNewWithoutOverride() {
    StorageDriverTargetInfo current =
        new StorageDriverWorkflowInfo("ns", "wf-1", "run-1", "CurrentWorkflow");
    Command command =
        Command.newBuilder()
            .setContinueAsNewWorkflowExecutionCommandAttributes(
                ContinueAsNewWorkflowExecutionCommandAttributes.newBuilder())
            .build();

    assertEquals(
        new StorageDriverWorkflowInfo("ns", "wf-1", null, "CurrentWorkflow"),
        WorkflowWorker.deriveStorageTarget("ns", current, command));
  }

  @Test
  public void deriveStorageTargetKeepsTheCurrentTargetForOtherCommands() {
    StorageDriverTargetInfo current =
        new StorageDriverWorkflowInfo("ns", "wf-1", "run-1", "MyWorkflow");
    Command command =
        Command.newBuilder()
            .setCompleteWorkflowExecutionCommandAttributes(
                CompleteWorkflowExecutionCommandAttributes.newBuilder())
            .build();

    assertSame(current, WorkflowWorker.deriveStorageTarget("ns", current, command));
  }
}
