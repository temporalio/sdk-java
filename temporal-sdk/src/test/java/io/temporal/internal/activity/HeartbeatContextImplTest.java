package io.temporal.internal.activity;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.uber.m3.tally.NoopScope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.activity.ActivityInfo;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.client.ActivityCanceledException;
import io.temporal.client.ActivityCompletionException;
import io.temporal.common.converter.GlobalDataConverter;
import io.temporal.failure.TimeoutFailure;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testUtils.Eventually;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

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

  private HeartbeatContextImpl createHeartbeatContext(ActivityInfo info) {
    return new HeartbeatContextImpl(
        service,
        "test-namespace",
        info,
        GlobalDataConverter.get(),
        heartbeatExecutor,
        new NoopScope(),
        "test-identity",
        Duration.ofSeconds(60),
        Duration.ofSeconds(30),
        TEST_BUFFER_MILLIS);
  }

  private static ActivityInfo activityInfoWithHeartbeatTimeout(Duration heartbeatTimeout) {
    ActivityInfo info = mock(ActivityInfo.class);
    when(info.getHeartbeatTimeout()).thenReturn(heartbeatTimeout);
    when(info.getTaskToken()).thenReturn(new byte[] {1, 2, 3});
    when(info.getWorkflowId()).thenReturn("test-workflow-id");
    when(info.getWorkflowType()).thenReturn("test-workflow-type");
    when(info.getActivityType()).thenReturn("test-activity-type");
    when(info.getActivityTaskQueue()).thenReturn("test-task-queue");
    when(info.getActivityId()).thenReturn("test-activity-id");
    when(info.isLocal()).thenReturn(false);
    when(info.getHeartbeatDetails()).thenReturn(Optional.empty());
    return info;
  }
}
