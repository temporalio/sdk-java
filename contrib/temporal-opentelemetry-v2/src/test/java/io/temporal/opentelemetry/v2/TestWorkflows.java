package io.temporal.opentelemetry.v2;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationErrorCategory;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.nexus.Nexus;
import io.temporal.nexus.WorkflowRunOperation;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.NexusOperationHandle;
import io.temporal.workflow.NexusOperationOptions;
import io.temporal.workflow.NexusServiceOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Workflow and activity fixtures used by the OpenTelemetry v2 tests. */
public final class TestWorkflows {
  private TestWorkflows() {}

  static final String TRACER_TEST_QUERY_NAME = "query";
  static final String TRACER_TEST_UPDATE_NAME = "update";
  static final String TRACER_TEST_SIGNAL_NAME = "gate";
  static final String NEXUS_SERVICE_NAME = "ComprehensiveNexusService";
  static final String NEXUS_OPERATION_NAME = "nexusHandlerWorkflow";
  static final String NEXUS_CANCEL_OPERATION_NAME = "nexusCancelHandlerWorkflow";

  /** Stands in for the external system an async activity hands its task token to. */
  static final BlockingQueue<byte[]> TASK_TOKENS = new LinkedBlockingQueue<>();

  static Span startSpan(String tracerName, String spanName) {
    return GlobalOpenTelemetry.getTracer(tracerName).spanBuilder(spanName).startSpan();
  }

  static void spanAround(String tracerName, String spanName, Runnable body) {
    Span span = startSpan(tracerName, spanName);
    try (Scope ignored = span.makeCurrent()) {
      body.run();
    } finally {
      span.end();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Activities
  // ---------------------------------------------------------------------------------------------

  @ActivityInterface
  public interface TestActivities {
    void activity();

    void localActivity();

    void asyncCompletionActivity();

    void standaloneActivity();

    void nopActivity();
  }

  public static class TestActivitiesImpl implements TestActivities {
    @Override
    public void activity() {
      spanAround("activity", "activity-span", () -> {});
    }

    @Override
    public void localActivity() {
      spanAround("localActivity", "local-activity-span", () -> {});
    }

    @Override
    public void asyncCompletionActivity() {
      TASK_TOKENS.add(Activity.getExecutionContext().getTaskToken());
      Activity.getExecutionContext().doNotCompleteOnReturn();
    }

    @Override
    public void standaloneActivity() {}

    @Override
    public void nopActivity() {}
  }

  // ---------------------------------------------------------------------------------------------
  // Interceptor tests
  // ---------------------------------------------------------------------------------------------

  @WorkflowInterface
  public interface SpanKindWorkflow {
    @WorkflowMethod
    void run();
  }

  public static class SpanKindWorkflowImpl implements SpanKindWorkflow {
    @Override
    public void run() {
      activities().nopActivity();
    }
  }

  @WorkflowInterface
  public interface AsyncLambdaWorkflow {
    @WorkflowMethod
    void run();
  }

  public static class AsyncLambdaWorkflowImpl implements AsyncLambdaWorkflow {
    @Override
    public void run() {
      Span parent = startSpan("asyncLambda", "parent");
      try (Scope ignored = parent.makeCurrent()) {
        Async.function(
                () -> {
                  Span child = startSpan("asyncLambda", "child");
                  try (Scope ignoredChild = child.makeCurrent()) {
                    activities().nopActivity();
                  } finally {
                    child.end();
                  }
                  return null;
                })
            .get();
      } finally {
        parent.end();
      }
    }
  }

  @WorkflowInterface
  public interface PromiseCallbackWorkflow {
    @WorkflowMethod
    void run();
  }

  public static class PromiseCallbackWorkflowImpl implements PromiseCallbackWorkflow {
    @Override
    public void run() {
      Span application = startSpan("promiseCallback", "application");
      try (Scope ignored = application.makeCurrent()) {
        Promise<Void> continuation =
            Async.procedure(activities()::asyncCompletionActivity)
                .thenApply(
                    ignoredResult -> {
                      Span callback = startSpan("promiseCallback", "callback");
                      try (Scope ignoredCallback = callback.makeCurrent()) {
                        activities().nopActivity();
                      } finally {
                        callback.end();
                      }
                      return null;
                    });
        continuation.get();
      } finally {
        application.end();
      }
    }
  }

  @WorkflowInterface
  public interface BenignErrorWorkflow {
    @WorkflowMethod
    void run();
  }

  public static class BenignErrorWorkflowImpl implements BenignErrorWorkflow {
    @Override
    public void run() {
      throw ApplicationFailure.newBuilder()
          .setMessage("expected error")
          .setType("BenignError")
          .setCategory(ApplicationErrorCategory.BENIGN)
          .build();
    }
  }

  @WorkflowInterface
  public interface ErrorWorkflow {
    @WorkflowMethod
    void run();
  }

  public static class ErrorWorkflowImpl implements ErrorWorkflow {
    @Override
    public void run() {
      throw ApplicationFailure.newFailure("unexpected error", "UnexpectedError");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Tracer tests
  // ---------------------------------------------------------------------------------------------

  @WorkflowInterface
  public interface TracerWorkflow {
    @WorkflowMethod
    void run(boolean end);

    @QueryMethod(name = TRACER_TEST_QUERY_NAME)
    String query();

    @UpdateValidatorMethod(updateName = TRACER_TEST_UPDATE_NAME)
    void validateUpdate();

    @UpdateMethod(name = TRACER_TEST_UPDATE_NAME)
    void update();

    @SignalMethod(name = TRACER_TEST_SIGNAL_NAME)
    void gate();
  }

  public static class TracerWorkflowImpl implements TracerWorkflow {
    private boolean gated;

    @Override
    public void run(boolean end) {
      if (!end) {
        Workflow.await(() -> gated);
      }

      // Both spans end before continue-as-new, so the next run's context starts from the root.
      Span beginProcessing = startSpan("processorTracer", "process start");
      try (Scope ignored = beginProcessing.makeCurrent()) {
        startSpan("recorderTracer", "record results").end();
      } finally {
        beginProcessing.end();
      }

      if (!end) {
        Workflow.continueAsNew(true);
      }
    }

    @Override
    public String query() {
      startSpan("queryTracer", "query start").end();
      return "ok";
    }

    @Override
    public void update() {
      startSpan("updateTracer", "update start").end();
    }

    @Override
    public void validateUpdate() {
      startSpan("validatorTracer", "validate start").end();
    }

    @Override
    public void gate() {
      gated = true;
    }
  }

  public enum WorkflowOutboundCall {
    CHILD_WORKFLOW,
    EXTERNAL_SIGNAL,
    EXTERNAL_CANCEL
  }

  @WorkflowInterface
  public interface WorkflowOutboundTagsWorkflow {
    @WorkflowMethod
    void run(WorkflowOutboundCall call, WorkflowExecution targetExecution);
  }

  public static class WorkflowOutboundTagsWorkflowImpl implements WorkflowOutboundTagsWorkflow {
    @Override
    public void run(WorkflowOutboundCall call, WorkflowExecution targetExecution) {
      switch (call) {
        case CHILD_WORKFLOW:
          ChildWorkflowWithSignal child =
              Workflow.newChildWorkflowStub(ChildWorkflowWithSignal.class);
          Promise<Void> childResult = Async.procedure(child::run);
          Workflow.getWorkflowExecution(child).get();
          child.childSignal();
          childResult.get();
          break;
        case EXTERNAL_SIGNAL:
          Workflow.newUntypedExternalWorkflowStub(targetExecution).signal("childSignal");
          break;
        case EXTERNAL_CANCEL:
          Workflow.newUntypedExternalWorkflowStub(targetExecution).cancel();
          break;
      }
    }
  }

  @WorkflowInterface
  public interface ChainedContinueAsNewWorkflow {
    @WorkflowMethod
    void run(boolean finalRun);
  }

  /** Continues as new inside its user span, so the next run parents under that span. */
  public static class ChainedContinueAsNewWorkflowImpl implements ChainedContinueAsNewWorkflow {
    @Override
    public void run(boolean finalRun) {
      spanAround(
          "chainedContinueAsNewWorkflow",
          "chained-span",
          () -> {
            if (!finalRun) {
              Workflow.continueAsNew(true);
            }
          });
    }
  }

  @WorkflowInterface
  public interface ContinueAsNewToDifferentWorkflow {
    @WorkflowMethod
    void run();
  }

  public static class ContinueAsNewToDifferentWorkflowImpl
      implements ContinueAsNewToDifferentWorkflow {
    @Override
    public void run() {
      Workflow.continueAsNew(DifferentWorkflow.class.getSimpleName(), null);
    }
  }

  @WorkflowInterface
  public interface DifferentWorkflow {
    @WorkflowMethod
    void run();
  }

  public static class DifferentWorkflowImpl implements DifferentWorkflow {
    @Override
    public void run() {}
  }

  @WorkflowInterface
  public interface TracerResetWorkflow {
    @WorkflowMethod
    void run();
  }

  public static class TracerResetWorkflowImpl implements TracerResetWorkflow {
    @Override
    public void run() {
      // Ended but kept current, so later spans parent to it.
      Span beginProcessing = startSpan("processorTracer", "process start");
      try (Scope ignored = beginProcessing.makeCurrent()) {
        beginProcessing.end();
        Workflow.newActivityStub(
                TestActivities.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build())
            .nopActivity();
        startSpan("recorderTracer", "record results").end();
      }
    }
  }

  @WorkflowInterface
  public interface TracerResetLateSourceWorkflow {
    @WorkflowMethod
    void run();
  }

  /** Obtains the second tracer only after the reset point. */
  public static class TracerResetLateSourceWorkflowImpl implements TracerResetLateSourceWorkflow {
    @Override
    public void run() {
      Span beginProcessing = startSpan("processorTracer", "process start");
      try (Scope ignored = beginProcessing.makeCurrent()) {
        beginProcessing.end();
        Workflow.newActivityStub(
                TestActivities.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build())
            .nopActivity();
        Tracer recorder = GlobalOpenTelemetry.getTracer("recorderTracer");
        recorder.spanBuilder("record results").startSpan().end();
      }
    }
  }

  @WorkflowInterface
  public interface TracerResetDuringSpan {
    @WorkflowMethod
    void run();
  }

  /** Both spans stay open across the reset point. */
  public static class TracerResetDuringSpanImpl implements TracerResetDuringSpan {
    @Override
    public void run() {
      Span beginProcessing = startSpan("processorTracer", "process start");
      try (Scope ignored = beginProcessing.makeCurrent()) {
        Span recordingResults = startSpan("recorderTracer", "record results");
        Workflow.newActivityStub(
                TestActivities.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build())
            .nopActivity();
        beginProcessing.end();
        recordingResults.end();
      }
    }
  }

  @WorkflowInterface
  public interface TracerWorkflowTaskRetry {
    @WorkflowMethod
    void run();
  }

  public static class TracerWorkflowTaskRetryImpl implements TracerWorkflowTaskRetry {
    @Override
    public void run() {
      startSpan("test", "workflow-task-retry-span").end();
      throw new RuntimeException("intentional workflow task failure");
    }
  }

  @WorkflowInterface
  public interface TracerSpanTimestampWorkflow {
    @WorkflowMethod
    void run();
  }

  public static class TracerSpanTimestampWorkflowImpl implements TracerSpanTimestampWorkflow {
    @Override
    public void run() {
      Span span =
          GlobalOpenTelemetry.getTracer("timestampTracer")
              .spanBuilder("explicit timestamp")
              .setStartTimestamp(123456789L, TimeUnit.MILLISECONDS)
              .startSpan();
      try {
        Workflow.newActivityStub(
                TestActivities.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build())
            .nopActivity();
      } finally {
        span.end();
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Comprehensive scenario
  // ---------------------------------------------------------------------------------------------

  @WorkflowInterface
  public interface ExternalWorkflowWithSignal {
    @WorkflowMethod
    String run();

    @SignalMethod
    void externalSignal();

    @SignalMethod
    void scheduleStarted(String workflowId);
  }

  public static class ExternalWorkflowWithSignalImpl implements ExternalWorkflowWithSignal {
    private boolean externalSignaled;
    private String scheduledWorkflowId;

    @Override
    public String run() {
      spanAround(
          "externalWorkflowWithSignal",
          "external-workflow-with-signal-span",
          () -> Workflow.await(() -> externalSignaled && scheduledWorkflowId != null));
      return scheduledWorkflowId;
    }

    @Override
    public void externalSignal() {
      externalSignaled = true;
    }

    @Override
    public void scheduleStarted(String workflowId) {
      scheduledWorkflowId = workflowId;
    }
  }

  @WorkflowInterface
  public interface ChildWorkflowWithSignal {
    @WorkflowMethod
    void run();

    @SignalMethod
    void childSignal();
  }

  public static class ChildWorkflowWithSignalImpl implements ChildWorkflowWithSignal {
    private boolean signaled;

    @Override
    public void run() {
      spanAround(
          "childWorkflowWithSignal",
          "child-workflow-with-signal-span",
          () -> Workflow.await(() -> signaled));
    }

    @Override
    public void childSignal() {
      signaled = true;
    }
  }

  @WorkflowInterface
  public interface NexusHandlerWorkflow {
    @WorkflowMethod
    Void run(String input);
  }

  public static class NexusHandlerWorkflowImpl implements NexusHandlerWorkflow {
    @Override
    public Void run(String input) {
      spanAround("workflowWithNexusHandler", "workflow-with-nexus-handler-span", () -> {});
      return null;
    }
  }

  @WorkflowInterface
  public interface NexusCancelHandlerWorkflow {
    @WorkflowMethod
    Void run(String input);
  }

  /** Waits until the Nexus caller cancels it. */
  public static class NexusCancelHandlerWorkflowImpl implements NexusCancelHandlerWorkflow {
    @Override
    public Void run(String input) {
      spanAround(
          "nexusCancelHandlerWorkflow",
          "nexus-cancel-handler-span",
          () -> Workflow.await(() -> false));
      return null;
    }
  }

  @Service(name = NEXUS_SERVICE_NAME)
  public interface ComprehensiveNexusService {
    @Operation(name = NEXUS_OPERATION_NAME)
    Void nexusHandlerWorkflow(String input);

    @Operation(name = NEXUS_CANCEL_OPERATION_NAME)
    Void nexusCancelHandlerWorkflow(String input);
  }

  @ServiceImpl(service = ComprehensiveNexusService.class)
  public static class ComprehensiveNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, Void> nexusHandlerWorkflow() {
      return WorkflowRunOperation.fromWorkflowMethod(
          (context, details, input) ->
              Nexus.getOperationContext()
                      .getWorkflowClient()
                      .newWorkflowStub(
                          NexusHandlerWorkflow.class,
                          WorkflowOptions.newBuilder()
                              .setWorkflowId("nexus-handler-" + details.getRequestId())
                              .build())
                  ::run);
    }

    @OperationImpl
    public OperationHandler<String, Void> nexusCancelHandlerWorkflow() {
      return WorkflowRunOperation.fromWorkflowMethod(
          (context, details, input) ->
              Nexus.getOperationContext()
                      .getWorkflowClient()
                      .newWorkflowStub(
                          NexusCancelHandlerWorkflow.class,
                          WorkflowOptions.newBuilder()
                              .setWorkflowId("nexus-cancel-handler-" + details.getRequestId())
                              .build())
                  ::run);
    }
  }

  @WorkflowInterface
  public interface ComprehensiveWorkflow {
    @WorkflowMethod
    void run(boolean finalRun, String nexusEndpoint, String externalWorkflowId);

    @QueryMethod(name = "getStatus")
    String getStatus();

    @UpdateMethod(name = "testUpdate")
    void testUpdate();

    @UpdateValidatorMethod(updateName = "testUpdate")
    void validateTestUpdate();

    @SignalMethod
    void proceed();
  }

  public static class ComprehensiveWorkflowImpl implements ComprehensiveWorkflow {
    private boolean proceed;

    @Override
    public void run(boolean finalRun, String nexusEndpoint, String externalWorkflowId) {
      // The returned context is discarded, so the span is not made current. The outbound calls
      // below parent to the RunWorkflow span and this span is their sibling.
      Span span = startSpan("comprehensiveWorkflow", "comprehensive-outbound-workflow-span");
      try {
        if (finalRun) {
          return;
        }

        Workflow.await(() -> proceed);

        activities().activity();
        Workflow.newLocalActivityStub(
                TestActivities.class,
                LocalActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .build())
            .localActivity();

        ChildWorkflowWithSignal child =
            Workflow.newChildWorkflowStub(ChildWorkflowWithSignal.class);
        Promise<Void> childResult = Async.procedure(child::run);
        Workflow.getWorkflowExecution(child).get();
        child.childSignal();
        childResult.get();

        Workflow.newExternalWorkflowStub(ExternalWorkflowWithSignal.class, externalWorkflowId)
            .externalSignal();

        ComprehensiveNexusService nexus =
            Workflow.newNexusServiceStub(
                ComprehensiveNexusService.class,
                NexusServiceOptions.newBuilder()
                    .setEndpoint(nexusEndpoint)
                    .setOperationOptions(
                        NexusOperationOptions.newBuilder()
                            .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                            .build())
                    .build());
        nexus.nexusHandlerWorkflow("");

        try {
          Workflow.newCancellationScope(
                  () -> {
                    NexusOperationHandle<Void> handle =
                        Workflow.startNexusOperation(nexus::nexusCancelHandlerWorkflow, "");
                    handle.getExecution().get();
                    CancellationScope.current().cancel();
                    handle.getResult().get();
                  })
              .run();
        } catch (NexusOperationFailure failure) {
          // Cancellation is expected.
          if (!(failure.getCause() instanceof CanceledFailure)) {
            throw failure;
          }
        }

        Workflow.continueAsNew(true, nexusEndpoint, externalWorkflowId);
      } finally {
        span.end();
      }
    }

    @Override
    public String getStatus() {
      spanAround(
          "comprehensiveWorkflow",
          "query-handler-span",
          () -> startSpan("comprehensiveWorkflow", "query-handler-child-span").end());
      return "ok";
    }

    @Override
    public void testUpdate() {
      spanAround(
          "comprehensiveWorkflow",
          "update-handler-span",
          () -> startSpan("comprehensiveWorkflow", "update-handler-child-span").end());
    }

    @Override
    public void validateTestUpdate() {
      spanAround(
          "comprehensiveWorkflow",
          "validate-update-span",
          () -> startSpan("comprehensiveWorkflow", "validate-update-span-child").end());
    }

    @Override
    public void proceed() {
      proceed = true;
    }
  }

  @WorkflowInterface
  public interface StandaloneWorkflow {
    @WorkflowMethod
    void run(String signalReceiverWorkflowId);
  }

  public static class StandaloneWorkflowImpl implements StandaloneWorkflow {
    @Override
    public void run(String signalReceiverWorkflowId) {
      if (signalReceiverWorkflowId != null) {
        spanAround(
            "standaloneWorkflow",
            "standalone-workflow-span",
            () ->
                Workflow.newExternalWorkflowStub(
                        ExternalWorkflowWithSignal.class, signalReceiverWorkflowId)
                    .scheduleStarted(Workflow.getInfo().getWorkflowId()));
      }
    }
  }

  @WorkflowInterface
  public interface SchedulePropagationWorkflow {
    @WorkflowMethod
    void run(String signalReceiverWorkflowId);
  }

  public static class SchedulePropagationWorkflowImpl implements SchedulePropagationWorkflow {
    @Override
    public void run(String signalReceiverWorkflowId) {
      spanAround(
          "schedulePropagationWorkflow",
          "schedule-propagation-workflow-span",
          () ->
              Workflow.newExternalWorkflowStub(
                      SchedulePropagationReceiver.class, signalReceiverWorkflowId)
                  .scheduleStarted(Workflow.getInfo().getWorkflowId()));
    }
  }

  @WorkflowInterface
  public interface SchedulePropagationReceiver {
    @WorkflowMethod
    String run();

    @SignalMethod
    void scheduleStarted(String workflowId);
  }

  public static class SchedulePropagationReceiverImpl implements SchedulePropagationReceiver {
    private String scheduledWorkflowId;

    @Override
    public String run() {
      Workflow.await(() -> scheduledWorkflowId != null);
      return scheduledWorkflowId;
    }

    @Override
    public void scheduleStarted(String workflowId) {
      scheduledWorkflowId = workflowId;
    }
  }

  @WorkflowInterface
  public interface UnservedWorkflow {
    @WorkflowMethod
    void run();
  }

  public static class UnservedWorkflowImpl implements UnservedWorkflow {
    @Override
    public void run() {}
  }

  @WorkflowInterface
  public interface SignalWithStartTarget {
    @WorkflowMethod
    void run();

    @SignalMethod
    void startSignal();
  }

  public static class SignalWithStartTargetImpl implements SignalWithStartTarget {
    private boolean signaled;

    @Override
    public void run() {
      spanAround(
          "signalWithStartTarget",
          "signal-with-start-target-span",
          () -> Workflow.await(() -> signaled));
    }

    @Override
    public void startSignal() {
      signaled = true;
    }
  }

  @WorkflowInterface
  public interface UpdateTargetWorkflow {
    @WorkflowMethod
    void run();

    @UpdateMethod(name = "doUpdate")
    void doUpdate();

    @SignalMethod
    void updateSignal();
  }

  public static class UpdateTargetWorkflowImpl implements UpdateTargetWorkflow {
    private boolean signaled;

    @Override
    public void run() {
      spanAround(
          "updateTargetWorkflow",
          "update-target-workflow-span",
          () -> Workflow.await(() -> signaled));
    }

    @Override
    public void doUpdate() {
      startSpan("updateTracer", "update start").end();
    }

    @Override
    public void updateSignal() {
      signaled = true;
    }
  }

  @WorkflowInterface
  public interface AsyncCompletionWorkflow {
    @WorkflowMethod
    void run();
  }

  public static class AsyncCompletionWorkflowImpl implements AsyncCompletionWorkflow {
    @Override
    public void run() {
      Workflow.newActivityStub(
              TestActivities.class,
              ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(15)).build())
          .asyncCompletionActivity();
    }
  }

  private static TestActivities activities() {
    return Workflow.newActivityStub(
        TestActivities.class,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());
  }
}
