package io.temporal.internal.activity;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.uber.m3.tally.NoopScope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.activity.ActivityInfo;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.client.ActivityCanceledException;
import io.temporal.client.ActivityCompletionException;
import io.temporal.client.WorkflowClient;
import io.temporal.common.CancellationToken;
import io.temporal.common.converter.GlobalDataConverter;
import io.temporal.failure.TimeoutFailure;
import io.temporal.internal.payload.storage.ExternalStorageRunner;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverActivityInfo;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testUtils.Eventually;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class HeartbeatContextImplTest {

  private static final long TEST_BUFFER_MILLIS = 200;

  private ScheduledExecutorService heartbeatExecutor;
  private WorkflowServiceStubs service;
  private WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub;

  @Before
  public void setUp() {
    heartbeatExecutor = Executors.newScheduledThreadPool(1);
    service = mock(WorkflowServiceStubs.class);
    blockingStub = mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(service.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);
  }

  @After
  public void tearDown() {
    heartbeatExecutor.shutdownNow();
  }

  @Test
  public void heartbeatTimeoutLocallyCancelsActivity() {
    Duration heartbeatTimeout = Duration.ofMillis(500);

    // All heartbeat RPCs fail with UNAVAILABLE
    when(blockingStub.recordActivityTaskHeartbeat(any()))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

    ActivityInfo info = activityInfoWithHeartbeatTimeout(heartbeatTimeout);
    HeartbeatContextImpl ctx = createHeartbeatContext(info);

    long startNanos = System.nanoTime();
    ctx.heartbeat("details-1");

    ActivityCompletionException caught =
        Eventually.assertEventually(
            Duration.ofSeconds(10),
            () -> {
              try {
                ctx.heartbeat("poll");
                fail("Expected ActivityCanceledException");
                return null;
              } catch (ActivityCompletionException e) {
                return e;
              }
            });

    long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

    assertSame(ActivityCanceledException.class, caught.getClass());
    assertNotNull("Expected a TimeoutFailure cause", caught.getCause());
    assertSame(TimeoutFailure.class, caught.getCause().getClass());
    assertEquals(
        TimeoutType.TIMEOUT_TYPE_HEARTBEAT, ((TimeoutFailure) caught.getCause()).getTimeoutType());
    long expectedMinMs = heartbeatTimeout.toMillis() + TEST_BUFFER_MILLIS;
    assertTrue(
        "Timeout should not fire before heartbeat timeout + buffer ("
            + elapsedMs
            + "ms elapsed, expected >= "
            + expectedMinMs
            + "ms)",
        elapsedMs >= expectedMinMs);

    ctx.cancelOutstandingHeartbeat();
  }

  @Test
  public void heartbeatTimeoutResetsOnSuccessfulSend() {
    Duration heartbeatTimeout = Duration.ofMillis(500);
    AtomicInteger callCount = new AtomicInteger();

    // First call succeeds, then all subsequent calls fail
    when(blockingStub.recordActivityTaskHeartbeat(any()))
        .thenAnswer(
            invocation -> {
              if (callCount.getAndIncrement() == 0) {
                return RecordActivityTaskHeartbeatResponse.getDefaultInstance();
              }
              throw new StatusRuntimeException(Status.UNAVAILABLE);
            });

    ActivityInfo info = activityInfoWithHeartbeatTimeout(heartbeatTimeout);
    HeartbeatContextImpl ctx = createHeartbeatContext(info);

    // The first heartbeat() call sends the RPC synchronously (no scheduled heartbeat yet).
    // Record the time before calling — the timer reset happens during this call.
    long resetNanos = System.nanoTime();
    ctx.heartbeat("details-1");
    assertEquals("First RPC should have been the successful one", 1, callCount.get());

    // Poll until the timeout fires again (from the reset point)
    Eventually.assertEventually(
        Duration.ofSeconds(10),
        () -> {
          try {
            ctx.heartbeat("poll");
            fail("Expected ActivityCanceledException");
          } catch (ActivityCanceledException e) {
            // expected
          }
        });

    long elapsedSinceResetMs = Duration.ofNanos(System.nanoTime() - resetNanos).toMillis();
    long expectedMinMs = heartbeatTimeout.toMillis() + TEST_BUFFER_MILLIS;
    assertTrue(
        "Timeout should not fire before heartbeat timeout + buffer from reset point ("
            + elapsedSinceResetMs
            + "ms elapsed since reset, expected >= "
            + expectedMinMs
            + "ms)",
        elapsedSinceResetMs >= expectedMinMs);

    ctx.cancelOutstandingHeartbeat();
  }

  @Test
  public void heartbeatTimeoutPersistsAcrossMultipleCalls() {
    Duration heartbeatTimeout = Duration.ofMillis(500);

    // All heartbeat RPCs fail with UNAVAILABLE
    when(blockingStub.recordActivityTaskHeartbeat(any()))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

    ActivityInfo info = activityInfoWithHeartbeatTimeout(heartbeatTimeout);
    HeartbeatContextImpl ctx = createHeartbeatContext(info);

    ctx.heartbeat("details-1");

    // Wait for timeout to fire
    Eventually.assertEventually(
        Duration.ofSeconds(10),
        () -> {
          try {
            ctx.heartbeat("poll");
            fail("Expected ActivityCanceledException");
          } catch (ActivityCanceledException e) {
            // expected
          }
        });

    // Subsequent calls should continue to throw
    for (int i = 0; i < 5; i++) {
      try {
        ctx.heartbeat("details-" + i);
        fail("Expected ActivityCanceledException on call " + i);
      } catch (ActivityCompletionException e) {
        assertSame(ActivityCanceledException.class, e.getClass());
        assertNotNull(e.getCause());
        assertSame(TimeoutFailure.class, e.getCause().getClass());
      }
    }

    ctx.cancelOutstandingHeartbeat();
  }

  @Test
  public void workerCommandCancelStillSendsHeartbeatDetails() {
    when(blockingStub.recordActivityTaskHeartbeat(any()))
        .thenReturn(RecordActivityTaskHeartbeatResponse.getDefaultInstance());

    ActivityInfo info = activityInfoWithHeartbeatTimeout(Duration.ofSeconds(10));
    HeartbeatContextImpl ctx =
        createHeartbeatContext(info, Duration.ofMillis(100), Duration.ofMillis(100));

    assertFalse(ctx.getCancellationToken().isCancellationRequested());
    assertFalse(ctx.getCancellationToken().getCancellationFuture().isDone());

    ctx.heartbeat("before-cancel");
    ctx.cancelFromWorkerCommand();

    assertTrue(ctx.getCancellationToken().isCancellationRequested());
    ActivityCanceledException exception =
        assertThrows(
            ActivityCanceledException.class,
            () -> ctx.getCancellationToken().throwIfCancellationRequested());
    assertSame(
        exception, assertCancellationFutureCompletedExceptionally(ctx.getCancellationToken()));

    try {
      ctx.heartbeat("after-cancel");
      fail("Expected ActivityCanceledException");
    } catch (ActivityCanceledException e) {
      assertNull(e.getCause());
    }

    ArgumentCaptor<RecordActivityTaskHeartbeatRequest> requestCaptor =
        ArgumentCaptor.forClass(RecordActivityTaskHeartbeatRequest.class);
    verify(blockingStub, timeout(1000).times(2))
        .recordActivityTaskHeartbeat(requestCaptor.capture());
    String details =
        GlobalDataConverter.get()
            .fromPayloads(
                0,
                Optional.of(requestCaptor.getAllValues().get(1).getDetails()),
                String.class,
                String.class);
    assertEquals("after-cancel", details);
    ctx.cancelOutstandingHeartbeat();
  }

  @Test
  public void completingReturnedCancellationFutureDoesNotCancelToken() {
    ActivityInfo info = activityInfoWithHeartbeatTimeout(Duration.ofSeconds(10));
    HeartbeatContextImpl ctx =
        createHeartbeatContext(info, Duration.ofMillis(100), Duration.ofMillis(100));

    CompletableFuture<Void> callerFuture = ctx.getCancellationToken().getCancellationFuture();
    callerFuture.complete(null);

    assertTrue(callerFuture.isDone());
    assertFalse(ctx.getCancellationToken().isCancellationRequested());
    ctx.getCancellationToken().throwIfCancellationRequested();

    ctx.cancelFromWorkerCommand();

    ActivityCanceledException exception =
        assertThrows(
            ActivityCanceledException.class,
            () -> ctx.getCancellationToken().throwIfCancellationRequested());
    assertSame(
        exception, assertCancellationFutureCompletedExceptionally(ctx.getCancellationToken()));

    ctx.cancelOutstandingHeartbeat();
  }

  @Test
  public void asyncCompletionRejectsNewHeartbeatsAndFlushesQueuedHeartbeat() {
    when(blockingStub.recordActivityTaskHeartbeat(any()))
        .thenReturn(RecordActivityTaskHeartbeatResponse.getDefaultInstance());

    ActivityInfo info = activityInfoWithHeartbeatTimeout(Duration.ofSeconds(10));
    HeartbeatContextImpl ctx =
        createHeartbeatContext(info, Duration.ofMillis(100), Duration.ofMillis(100));

    ctx.heartbeat("sent-before-return");
    ctx.heartbeat("queued-before-return");
    ctx.asyncCompletionStarted();

    assertFalse(ctx.getCancellationToken().isCancellationRequested());
    assertFalse(ctx.getCancellationToken().getCancellationFuture().isDone());
    assertThrows(IllegalStateException.class, () -> ctx.heartbeat("after-return"));

    ArgumentCaptor<RecordActivityTaskHeartbeatRequest> requestCaptor =
        ArgumentCaptor.forClass(RecordActivityTaskHeartbeatRequest.class);
    verify(blockingStub, timeout(1000).times(2))
        .recordActivityTaskHeartbeat(requestCaptor.capture());
    String details =
        GlobalDataConverter.get()
            .fromPayloads(
                0,
                Optional.of(requestCaptor.getAllValues().get(1).getDetails()),
                String.class,
                String.class);
    assertEquals("queued-before-return", details);
    ctx.cancelOutstandingHeartbeat();
  }

  @Test
  public void heartbeatCancelCompletesCancellationToken() {
    when(blockingStub.recordActivityTaskHeartbeat(any()))
        .thenReturn(
            RecordActivityTaskHeartbeatResponse.newBuilder().setCancelRequested(true).build());

    ActivityInfo info = activityInfoWithHeartbeatTimeout(Duration.ofSeconds(10));
    HeartbeatContextImpl ctx = createHeartbeatContext(info);

    assertFalse(ctx.getCancellationToken().isCancellationRequested());
    assertFalse(ctx.getCancellationToken().getCancellationFuture().isDone());

    assertThrows(ActivityCanceledException.class, () -> ctx.heartbeat("details"));

    assertTrue(ctx.getCancellationToken().isCancellationRequested());
    ActivityCanceledException exception =
        assertThrows(
            ActivityCanceledException.class,
            () -> ctx.getCancellationToken().throwIfCancellationRequested());
    assertSame(
        exception, assertCancellationFutureCompletedExceptionally(ctx.getCancellationToken()));

    ctx.cancelOutstandingHeartbeat();
  }

  @Test
  public void factoryCancelByTaskTokenCompletesCancellationToken() {
    WorkflowClient client = mock(WorkflowClient.class);
    when(client.getWorkflowServiceStubs()).thenReturn(service);

    ActivityExecutionContextFactoryImpl factory =
        new ActivityExecutionContextFactoryImpl(
            client,
            "test-identity",
            "test-namespace",
            Duration.ofSeconds(60),
            Duration.ofSeconds(30),
            GlobalDataConverter.get(),
            heartbeatExecutor,
            null);

    ActivityInfoInternal info = activityInfoWithHeartbeatTimeout(Duration.ofSeconds(10));
    InternalActivityExecutionContext context =
        factory.createContext(info, new Object(), new NoopScope());

    assertFalse(context.getCancellationToken().isCancellationRequested());
    assertFalse(factory.cleanupContext(new byte[] {9, 8, 7}, true));
    assertTrue(factory.cleanupContext(new byte[] {1, 2, 3}, true));
    assertTrue(context.getCancellationToken().isCancellationRequested());
    assertCancellationFutureCompletedExceptionally(context.getCancellationToken());

    context.cancelOutstandingHeartbeat();
    assertFalse(factory.cleanupContext(new byte[] {1, 2, 3}, true));
  }

  private HeartbeatContextImpl createHeartbeatContext(ActivityInfo info) {
    return createHeartbeatContext(info, Duration.ofSeconds(60), Duration.ofSeconds(30));
  }

  private HeartbeatContextImpl createHeartbeatContext(
      ActivityInfo info,
      Duration maxHeartbeatThrottleInterval,
      Duration defaultHeartbeatThrottleInterval) {
    return new HeartbeatContextImpl(
        service,
        "test-namespace",
        info,
        GlobalDataConverter.get(),
        heartbeatExecutor,
        new NoopScope(),
        "test-identity",
        maxHeartbeatThrottleInterval,
        defaultHeartbeatThrottleInterval,
        null,
        TEST_BUFFER_MILLIS);
  }

  private static ActivityCanceledException assertCancellationFutureCompletedExceptionally(
      CancellationToken<ActivityCanceledException> cancellationToken) {
    CompletableFuture<Void> cancellationFuture = cancellationToken.getCancellationFuture();
    assertTrue(cancellationFuture.isDone());
    assertTrue(cancellationFuture.isCompletedExceptionally());
    ExecutionException exception = assertThrows(ExecutionException.class, cancellationFuture::get);
    assertSame(ActivityCanceledException.class, exception.getCause().getClass());
    return (ActivityCanceledException) exception.getCause();
  }

  private static ActivityInfoInternal activityInfoWithHeartbeatTimeout(Duration heartbeatTimeout) {
    ActivityInfoInternal info = mock(ActivityInfoInternal.class);
    when(info.getHeartbeatTimeout()).thenReturn(heartbeatTimeout);
    when(info.getTaskToken()).thenReturn(new byte[] {1, 2, 3});
    when(info.getWorkflowId()).thenReturn("test-workflow-id");
    when(info.getWorkflowType()).thenReturn("test-workflow-type");
    when(info.getActivityType()).thenReturn("test-activity-type");
    when(info.getActivityTaskQueue()).thenReturn("test-task-queue");
    when(info.getActivityId()).thenReturn("test-activity-id");
    when(info.isLocal()).thenReturn(false);
    when(info.getHeartbeatDetails()).thenReturn(Optional.empty());
    when(info.getCompletionHandle()).thenReturn(() -> {});
    return info;
  }

  @Test
  public void storageTargetForStandaloneActivityTargetsTheActivity() {
    ActivityInfo info = mock(ActivityInfo.class);
    when(info.getActivityRunId()).thenReturn("act-run-1");
    when(info.getActivityId()).thenReturn("act-1");
    when(info.getActivityType()).thenReturn("MyActivity");

    assertEquals(
        new StorageDriverActivityInfo("ns", "act-1", "act-run-1", "MyActivity"),
        HeartbeatContextImpl.storageTargetForActivity("ns", info));
  }

  @Test
  public void storageTargetForWorkflowActivityTargetsTheWorkflow() {
    ActivityInfo info = mock(ActivityInfo.class);
    when(info.getActivityRunId()).thenReturn(null);
    when(info.getWorkflowId()).thenReturn("wf-1");
    when(info.getWorkflowRunId()).thenReturn("wf-run-1");
    when(info.getWorkflowType()).thenReturn("MyWorkflow");

    assertEquals(
        new StorageDriverWorkflowInfo("ns", "wf-1", "wf-run-1", "MyWorkflow"),
        HeartbeatContextImpl.storageTargetForActivity("ns", info));
  }

  private static final Duration OFFLOAD_INTERVAL = Duration.ofMillis(500);

  @Test
  public void aNewerHeartbeatAbandonsAnInFlightOffload() throws Exception {
    BlockingDriver driver = new BlockingDriver();
    HeartbeatContextImpl ctx = createHeartbeatContextWithStorage(driver);

    Thread first = new Thread(() -> ctx.heartbeat(largeDetails("first")));
    first.start();
    assertTrue("the first store should start", driver.storeStarted.await(5, TimeUnit.SECONDS));

    ctx.heartbeat(largeDetails("second"));
    first.join(5000);
    assertFalse("the superseded heartbeat should not still be running", first.isAlive());

    assertEquals("both heartbeats should attempt a store", 2, driver.stores.get());
    verify(blockingStub, times(1))
        .recordActivityTaskHeartbeat(any(RecordActivityTaskHeartbeatRequest.class));
    assertTrue(
        "the abandoned store should have its cancellation token tripped", driver.firstCancelled());
  }

  @Test
  public void aThrottledOffloadIsAbandonedWhenTheHeartbeatPoolHasNoSpareThread() throws Exception {
    when(blockingStub.recordActivityTaskHeartbeat(any(RecordActivityTaskHeartbeatRequest.class)))
        .thenReturn(RecordActivityTaskHeartbeatResponse.getDefaultInstance());
    BlockingDriver driver = new BlockingDriver();
    HeartbeatContextImpl ctx = createHeartbeatContextWithStorage(driver);

    ctx.heartbeat("small");
    ctx.heartbeat(largeDetails("throttled"));

    assertTrue(
        "the throttled heartbeat should offload on the heartbeat pool",
        driver.storeStarted.await(5, TimeUnit.SECONDS));
    Eventually.assertEventually(
        Duration.ofSeconds(5),
        () ->
            assertTrue(
                "the offload must be abandoned even though it occupies the only pool thread",
                driver.firstCancelled()));
  }

  @Test
  public void activityCancellationAbandonsAnInFlightOffload() throws Exception {
    BlockingDriver driver = new BlockingDriver();
    HeartbeatContextImpl ctx = createHeartbeatContextWithStorage(driver);

    Thread heartbeating = new Thread(() -> ctx.heartbeat(largeDetails("cancelled")));
    heartbeating.start();
    assertTrue("the store should start", driver.storeStarted.await(5, TimeUnit.SECONDS));

    long startNanos = System.nanoTime();
    ctx.cancelFromWorkerCommand();
    heartbeating.join(5000);
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

    assertFalse("cancellation should not wait for the driver", heartbeating.isAlive());
    assertTrue(
        "cancellation should abandon the store rather than wait out the heartbeat interval, took "
            + elapsedMillis
            + "ms",
        elapsedMillis < OFFLOAD_INTERVAL.toMillis() / 2);
    assertTrue("the abandoned store should be cancelled", driver.firstCancelled());
  }

  private HeartbeatContextImpl createHeartbeatContextWithStorage(StorageDriver driver) {
    ExternalStorageRunner runner =
        ExternalStorageRunner.create(
            ExternalStorage.newBuilder().setDriver(driver).setPayloadSizeThreshold(100).build());
    return new HeartbeatContextImpl(
        service,
        "test-namespace",
        activityInfoWithHeartbeatTimeout(Duration.ZERO),
        GlobalDataConverter.get(),
        heartbeatExecutor,
        new NoopScope(),
        "test-identity",
        Duration.ofSeconds(60),
        OFFLOAD_INTERVAL,
        runner,
        TEST_BUFFER_MILLIS);
  }

  private static String largeDetails(String tag) {
    return tag + String.join("", Collections.nCopies(20, "0123456789"));
  }

  /** Never completes its first store, so the SDK has to abandon it. */
  private static final class BlockingDriver implements StorageDriver {
    final CountDownLatch storeStarted = new CountDownLatch(1);
    final AtomicInteger stores = new AtomicInteger();
    private final Map<String, Payload> objects = new HashMap<>();
    private volatile CancellationToken<?> firstStoreToken;
    private int counter = 0;

    boolean firstCancelled() {
      CancellationToken<?> token = firstStoreToken;
      return token != null && token.isCancellationRequested();
    }

    @Override
    public String getName() {
      return "blocking";
    }

    @Override
    public String getType() {
      return "test.blocking";
    }

    @Override
    public synchronized CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      if (stores.getAndIncrement() == 0) {
        firstStoreToken = context.getCancellationToken();
        storeStarted.countDown();
        return new CompletableFuture<>();
      }
      List<StorageDriverClaim> claims = new ArrayList<>();
      for (Payload payload : payloads) {
        String key = "k-" + (counter++);
        objects.put(key, payload);
        claims.add(new StorageDriverClaim(Collections.singletonMap("key", key)));
      }
      return CompletableFuture.completedFuture(claims);
    }

    @Override
    public synchronized CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      List<Payload> payloads = new ArrayList<>();
      for (StorageDriverClaim claim : claims) {
        payloads.add(objects.get(claim.getClaimData().get("key")));
      }
      return CompletableFuture.completedFuture(payloads);
    }
  }
}
