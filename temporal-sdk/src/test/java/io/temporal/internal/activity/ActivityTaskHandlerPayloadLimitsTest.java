package io.temporal.internal.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.Scope;
import io.grpc.Status;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.failure.v1.ApplicationFailureInfo;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse;
import io.temporal.api.workflowservice.v1.RespondActivityTaskFailedResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.client.ActivityCanceledException;
import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.GlobalDataConverter;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.internal.payload.limits.LimitClass;
import io.temporal.internal.payload.limits.LimitSeverity;
import io.temporal.internal.payload.limits.PayloadErrorLimits;
import io.temporal.internal.payload.limits.PayloadLimitViolation;
import io.temporal.internal.payload.limits.PayloadLimitViolationException;
import io.temporal.internal.worker.ActivityTask;
import io.temporal.internal.worker.ActivityTaskHandler;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.tuning.SlotPermit;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

/**
 * An oversized heartbeat makes the worker fail the activity itself, so the activity's own result
 * must not be sent afterwards: the server would answer it with NOT_FOUND.
 */
public class ActivityTaskHandlerPayloadLimitsTest {

  @ActivityInterface
  public interface HeartbeatingActivity {
    void run();
  }

  public static class HeartbeatingActivityImpl implements HeartbeatingActivity {
    private final boolean swallowCancellation;

    HeartbeatingActivityImpl(boolean swallowCancellation) {
      this.swallowCancellation = swallowCancellation;
    }

    @Override
    public void run() {
      try {
        Activity.getExecutionContext().heartbeat("details");
      } catch (ActivityCanceledException e) {
        if (!swallowCancellation) {
          throw e;
        }
      }
    }
  }

  /**
   * Sends a heartbeat, then queues one that goes out on the heartbeat thread, and returns while
   * that one is still being sent.
   */
  public static class ReturnsDuringHeartbeatActivityImpl implements HeartbeatingActivity {
    private final CountDownLatch sendQueuedHeartbeat;
    private final CountDownLatch heartbeatSending;

    ReturnsDuringHeartbeatActivityImpl(
        CountDownLatch sendQueuedHeartbeat, CountDownLatch heartbeatSending) {
      this.sendQueuedHeartbeat = sendQueuedHeartbeat;
      this.heartbeatSending = heartbeatSending;
    }

    @Override
    public void run() {
      Activity.getExecutionContext().heartbeat("sent");
      Activity.getExecutionContext().heartbeat("queued");
      sendQueuedHeartbeat.countDown();
      try {
        heartbeatSending.await();
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  }

  /** Runs each scheduled heartbeat as soon as the gate opens, regardless of its delay. */
  private static class GatedHeartbeatExecutor extends ScheduledThreadPoolExecutor {
    private final CountDownLatch gate;

    GatedHeartbeatExecutor(CountDownLatch gate) {
      super(1);
      this.gate = gate;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      return super.schedule(
          () -> {
            try {
              gate.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return;
            }
            command.run();
          },
          0,
          unit);
    }
  }

  private ScheduledExecutorService heartbeatExecutor;
  private ListAppender<ILoggingEvent> executorLogs;
  private WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub;
  private WorkflowClient client;

  @Before
  public void setUp() {
    heartbeatExecutor = Executors.newScheduledThreadPool(1);
    LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
    executorLogs = new ListAppender<>();
    executorLogs.setContext(loggerContext);
    executorLogs.start();
    loggerContext
        .getLogger(ActivityTaskExecutors.ActivityTaskExecutor.class.getName())
        .addAppender(executorLogs);
    WorkflowServiceStubs service = mock(WorkflowServiceStubs.class);
    blockingStub = mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(service.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);
    when(service.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.getDefaultInstance());
    PayloadLimitViolation violation =
        new PayloadLimitViolation("details", LimitClass.BLOB, LimitSeverity.ERROR, 1000, 100);
    when(blockingStub.recordActivityTaskHeartbeat(any()))
        .thenThrow(
            Status.INVALID_ARGUMENT
                .withDescription(violation.getMessage())
                .withCause(new PayloadLimitViolationException(violation))
                .asRuntimeException());
    client = mock(WorkflowClient.class);
    when(client.getWorkflowServiceStubs()).thenReturn(service);
  }

  @After
  public void tearDown() {
    heartbeatExecutor.shutdownNow();
    ((LoggerContext) LoggerFactory.getILoggerFactory())
        .getLogger(ActivityTaskExecutors.ActivityTaskExecutor.class.getName())
        .detachAppender(executorLogs);
  }

  @Test
  public void aRethrownCancellationSendsNothing() {
    when(blockingStub.respondActivityTaskFailed(any()))
        .thenReturn(RespondActivityTaskFailedResponse.getDefaultInstance());

    ActivityTaskHandler.Result result = runActivity(new HeartbeatingActivityImpl(false));

    verify(blockingStub).respondActivityTaskFailed(any());
    assertSendsNothing(result);
    assertNotLoggedAsCanceled();
  }

  @Test
  public void aSwallowedCancellationSendsNothing() {
    when(blockingStub.respondActivityTaskFailed(any()))
        .thenReturn(RespondActivityTaskFailedResponse.getDefaultInstance());

    ActivityTaskHandler.Result result = runActivity(new HeartbeatingActivityImpl(true));

    assertSendsNothing(result);
  }

  @Test
  public void aRethrownCancellationReportsTheFailureIfTheHeartbeatCouldNotReportIt() {
    // FAILED_PRECONDITION is not retried, so the heartbeat's report fails at once.
    when(blockingStub.respondActivityTaskFailed(any()))
        .thenThrow(Status.FAILED_PRECONDITION.asRuntimeException());

    ActivityTaskHandler.Result result = runActivity(new HeartbeatingActivityImpl(false));

    assertReportsPayloadsTooLarge(result);
    assertNotLoggedAsCanceled();
  }

  @Test
  public void aSwallowedCancellationReportsTheFailureIfTheHeartbeatCouldNotReportIt() {
    when(blockingStub.respondActivityTaskFailed(any()))
        .thenThrow(Status.FAILED_PRECONDITION.asRuntimeException());

    ActivityTaskHandler.Result result = runActivity(new HeartbeatingActivityImpl(true));

    assertReportsPayloadsTooLarge(result);
  }

  @Test
  public void aHeartbeatRejectedAfterTheActivityReturnsDecidesItsOutcome() {
    CountDownLatch sendQueuedHeartbeat = new CountDownLatch(1);
    CountDownLatch heartbeatSending = new CountDownLatch(1);
    CountDownLatch rejectHeartbeat = new CountDownLatch(1);
    PayloadLimitViolation violation =
        new PayloadLimitViolation("details", LimitClass.BLOB, LimitSeverity.ERROR, 1000, 100);
    AtomicInteger heartbeats = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (heartbeats.incrementAndGet() == 1) {
                return RecordActivityTaskHeartbeatResponse.getDefaultInstance();
              }
              heartbeatSending.countDown();
              rejectHeartbeat.await();
              throw Status.INVALID_ARGUMENT
                  .withDescription(violation.getMessage())
                  .withCause(new PayloadLimitViolationException(violation))
                  .asRuntimeException();
            })
        .when(blockingStub)
        .recordActivityTaskHeartbeat(any());
    when(blockingStub.respondActivityTaskFailed(any()))
        .thenReturn(RespondActivityTaskFailedResponse.getDefaultInstance());

    heartbeatExecutor.shutdownNow();
    heartbeatExecutor = new GatedHeartbeatExecutor(sendQueuedHeartbeat);

    // The heartbeat is rejected once the executor starts deciding the activity's outcome, which
    // begins by stopping heartbeats.
    ActivityTaskHandler.Result result =
        runActivity(
            new ReturnsDuringHeartbeatActivityImpl(sendQueuedHeartbeat, heartbeatSending),
            context -> {
              InternalActivityExecutionContext spy = spy(context);
              doAnswer(
                      invocation -> {
                        rejectHeartbeat.countDown();
                        return invocation.callRealMethod();
                      })
                  .when(spy)
                  .cancelOutstandingHeartbeat();
              return spy;
            });

    verify(blockingStub).respondActivityTaskFailed(any());
    assertSendsNothing(result);
  }

  /** Asserts that the activity reports the PayloadsTooLarge failure rather than its own outcome. */
  private static void assertReportsPayloadsTooLarge(ActivityTaskHandler.Result result) {
    assertNull(result.getTaskCompleted());
    assertNull(result.getTaskCanceled());
    assertNotNull(result.getTaskFailed());
    ApplicationFailureInfo info =
        result.getTaskFailed().getTaskFailedRequest().getFailure().getApplicationFailureInfo();
    assertEquals("PayloadsTooLarge", info.getType());
    assertFalse(info.getNonRetryable());
  }

  /** Asserts that the activity, which was failed, is not logged as canceled. */
  private void assertNotLoggedAsCanceled() {
    for (ILoggingEvent event : executorLogs.list) {
      assertFalse(event.getFormattedMessage(), event.getFormattedMessage().contains("canceled"));
    }
  }

  private ActivityTaskHandler.Result runActivity(HeartbeatingActivity activity) {
    return runActivity(activity, UnaryOperator.identity());
  }

  private ActivityTaskHandler.Result runActivity(
      HeartbeatingActivity activity,
      UnaryOperator<InternalActivityExecutionContext> wrapContext) {
    ActivityExecutionContextFactoryImpl factory =
        new ActivityExecutionContextFactoryImpl(
            client,
            "test-identity",
            "test-namespace",
            Duration.ofSeconds(60),
            Duration.ofSeconds(30),
            GlobalDataConverter.get(),
            heartbeatExecutor,
            null,
            () -> new PayloadErrorLimits(100, 100)) {
          @Override
          public InternalActivityExecutionContext createContext(
              ActivityInfoInternal info, Object activity, Scope metricsScope) {
            return wrapContext.apply(super.createContext(info, activity, metricsScope));
          }
        };
    ActivityTaskHandlerImpl handler =
        new ActivityTaskHandlerImpl(
            "test-namespace",
            "test-task-queue",
            GlobalDataConverter.get(),
            factory,
            new WorkerInterceptor[0],
            null);
    handler.registerActivityImplementations(new Object[] {activity});
    PollActivityTaskQueueResponse task =
        PollActivityTaskQueueResponse.newBuilder()
            .setTaskToken(ByteString.copyFrom("token", StandardCharsets.UTF_8))
            .setActivityId("activity-id")
            .setActivityType(ActivityType.newBuilder().setName("Run"))
            .setWorkflowType(WorkflowType.newBuilder().setName("Workflow"))
            .setWorkflowExecution(
                WorkflowExecution.newBuilder().setWorkflowId("workflow-id").setRunId("run-id"))
            .build();
    return handler.handle(
        new ActivityTask(task, new SlotPermit(), () -> {}), new NoopScope(), false);
  }

  private static void assertSendsNothing(ActivityTaskHandler.Result result) {
    assertNull(result.getTaskCompleted());
    assertNull(result.getTaskFailed());
    assertNull(result.getTaskCanceled());
    assertFalse(result.isManualCompletion());
  }
}
