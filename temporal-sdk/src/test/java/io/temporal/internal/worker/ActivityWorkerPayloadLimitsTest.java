package io.temporal.internal.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.namespace.v1.NamespaceInfo;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedResponse;
import io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskFailedResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.internal.payload.limits.LimitClass;
import io.temporal.internal.payload.limits.LimitSeverity;
import io.temporal.internal.payload.limits.PayloadErrorLimits;
import io.temporal.internal.payload.limits.PayloadLimitViolation;
import io.temporal.internal.payload.limits.PayloadLimitViolationException;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.MetricsType;
import io.temporal.worker.tuning.FixedSizeSlotSupplier;
import io.temporal.worker.tuning.PollerBehaviorSimpleMaximum;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

public class ActivityWorkerPayloadLimitsTest {

  private static final String ERROR_MESSAGE =
      "[TMPRL1103] Attempted to upload payloads with size that exceeded the error limit.";

  private final TestStatsReporter reporter = new TestStatsReporter();

  @Test
  public void aResultOverThePayloadErrorLimitFailsTheActivityRetryably() throws Exception {
    ArgumentCaptor<RespondActivityTaskFailedRequest> sent =
        ArgumentCaptor.forClass(RespondActivityTaskFailedRequest.class);
    CountDownLatch failed = new CountDownLatch(1);

    runOneActivityTask(
        payloadErrorLimits(),
        blockingStub -> {
          when(blockingStub.respondActivityTaskCompleted(
                  any(RespondActivityTaskCompletedRequest.class)))
              .thenThrow(payloadLimitViolation());
          when(blockingStub.respondActivityTaskFailed(any(RespondActivityTaskFailedRequest.class)))
              .thenAnswer(
                  (Answer<RespondActivityTaskFailedResponse>)
                      invocation -> {
                        failed.countDown();
                        return RespondActivityTaskFailedResponse.getDefaultInstance();
                      });
        },
        failed,
        blockingStub -> verify(blockingStub).respondActivityTaskFailed(sent.capture()));

    Failure failure = sent.getValue().getFailure();
    assertEquals(ERROR_MESSAGE, failure.getMessage());
    assertEquals("PayloadsTooLarge", failure.getApplicationFailureInfo().getType());
    assertFalse(failure.getApplicationFailureInfo().getNonRetryable());
    reporter.assertCounter(
        MetricsType.ACTIVITY_EXEC_FAILED_COUNTER,
        new ImmutableMap.Builder<String, String>()
            .put("worker_type", "ActivityWorker")
            .put("workflow_type", "Workflow")
            .put("activity_type", "Activity")
            .put(MetricsTag.TASK_FAILURE_TYPE, MetricsTag.TASK_FAILURE_VALUE_PAYLOADS_TOO_LARGE)
            .build(),
        1);
  }

  @Test
  public void completionsCarryTheNamespacePayloadErrorLimits() throws Exception {
    NamespaceCapabilities capabilities = payloadErrorLimits();
    CountDownLatch completed = new CountDownLatch(1);

    runOneActivityTask(
        capabilities,
        blockingStub ->
            when(blockingStub.respondActivityTaskCompleted(
                    any(RespondActivityTaskCompletedRequest.class)))
                .thenAnswer(
                    (Answer<RespondActivityTaskCompletedResponse>)
                        invocation -> {
                          completed.countDown();
                          return RespondActivityTaskCompletedResponse.getDefaultInstance();
                        }),
        completed,
        blockingStub ->
            verify(blockingStub)
                .withOption(
                    PayloadErrorLimits.CALL_OPTIONS_KEY, capabilities.getPayloadErrorLimits()));
  }

  private static NamespaceCapabilities payloadErrorLimits() {
    NamespaceCapabilities capabilities = new NamespaceCapabilities();
    capabilities.setFromLimits(
        NamespaceInfo.Limits.newBuilder()
            .setBlobSizeLimitError(100)
            .setMemoSizeLimitError(100)
            .build());
    return capabilities;
  }

  /** Returns what the payload limits interceptor throws for a completion over an error limit. */
  private static StatusRuntimeException payloadLimitViolation() {
    PayloadLimitViolation violation =
        new PayloadLimitViolation("result", LimitClass.BLOB, LimitSeverity.ERROR, 1000, 100);
    return Status.INVALID_ARGUMENT
        .withDescription(violation.getMessage())
        .withCause(new PayloadLimitViolationException(violation))
        .asRuntimeException();
  }

  /**
   * Polls one activity task whose handler completes it, runs {@code stubSetup}, waits for {@code
   * done}, then verifies.
   */
  @SuppressWarnings("deprecation")
  private void runOneActivityTask(
      NamespaceCapabilities namespaceCapabilities,
      Consumer<WorkflowServiceGrpc.WorkflowServiceBlockingStub> stubSetup,
      CountDownLatch done,
      Consumer<WorkflowServiceGrpc.WorkflowServiceBlockingStub> verification)
      throws Exception {
    WorkflowServiceStubs service = mock(WorkflowServiceStubs.class);
    when(service.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.getDefaultInstance());
    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(service.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);

    PollActivityTaskQueueResponse task =
        PollActivityTaskQueueResponse.newBuilder()
            .setTaskToken(ByteString.copyFrom("token", StandardCharsets.UTF_8))
            .setActivityId("activity-id")
            .setActivityType(ActivityType.newBuilder().setName("Activity"))
            .setWorkflowType(WorkflowType.newBuilder().setName("Workflow"))
            .setWorkflowExecution(
                WorkflowExecution.newBuilder().setWorkflowId("workflow-id").setRunId("run-id"))
            .build();
    CountDownLatch blockPolls = new CountDownLatch(1);
    when(blockingStub.pollActivityTaskQueue(any(PollActivityTaskQueueRequest.class)))
        .thenReturn(task)
        .thenAnswer(
            (Answer<PollActivityTaskQueueResponse>)
                invocation -> {
                  blockPolls.await();
                  return null;
                });
    stubSetup.accept(blockingStub);

    ActivityTaskHandler handler = mock(ActivityTaskHandler.class);
    when(handler.isAnyTypeSupported()).thenReturn(true);
    when(handler.handle(any(ActivityTask.class), any(Scope.class), anyBoolean()))
        .thenReturn(
            new ActivityTaskHandler.Result(
                "activity-id",
                RespondActivityTaskCompletedRequest.getDefaultInstance(),
                null,
                null,
                false));

    Scope metricsScope =
        new RootScopeBuilder()
            .reporter(reporter)
            .reportEvery(com.uber.m3.util.Duration.ofMillis(1));
    ActivityWorker worker =
        new ActivityWorker(
            service,
            "default",
            "task_queue",
            1.0,
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
            handler,
            new FixedSizeSlotSupplier<>(10),
            namespaceCapabilities);

    assertTrue(worker.start());
    assertTrue(done.await(10, TimeUnit.SECONDS));
    worker.shutdown(new ShutdownManager(), false).get();
    blockPolls.countDown();
    verification.accept(blockingStub);
  }
}
