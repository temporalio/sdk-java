package io.temporal.internal.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import io.grpc.Status;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

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

  private ScheduledExecutorService heartbeatExecutor;
  private WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub;
  private WorkflowClient client;

  @Before
  public void setUp() {
    heartbeatExecutor = Executors.newScheduledThreadPool(1);
    WorkflowServiceStubs service = mock(WorkflowServiceStubs.class);
    blockingStub = mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(service.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);
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
  }

  @Test
  public void aRethrownCancellationSendsNothing() {
    when(blockingStub.respondActivityTaskFailed(any()))
        .thenReturn(RespondActivityTaskFailedResponse.getDefaultInstance());

    ActivityTaskHandler.Result result = runActivity(new HeartbeatingActivityImpl(false));

    verify(blockingStub).respondActivityTaskFailed(any());
    assertSendsNothing(result);
  }

  @Test
  public void aSwallowedCancellationSendsNothing() {
    when(blockingStub.respondActivityTaskFailed(any()))
        .thenReturn(RespondActivityTaskFailedResponse.getDefaultInstance());

    ActivityTaskHandler.Result result = runActivity(new HeartbeatingActivityImpl(true));

    assertSendsNothing(result);
  }

  @Test
  public void theActivityReportsItsOwnResultIfFailingItDidNotSucceed() {
    when(blockingStub.respondActivityTaskFailed(any()))
        .thenThrow(Status.UNAVAILABLE.asRuntimeException());

    ActivityTaskHandler.Result result = runActivity(new HeartbeatingActivityImpl(false));

    assertNotNull(result.getTaskCanceled());
  }

  private ActivityTaskHandler.Result runActivity(HeartbeatingActivity activity) {
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
            () -> new PayloadErrorLimits(100, 100));
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
