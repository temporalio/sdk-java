package io.temporal.opentelemetry.v2.internal;

import static io.temporal.opentelemetry.v2.internal.TagKeys.*;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptorBase;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.TimerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInfo;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

public class OpenTelemetryWorkflowOutboundCallsInterceptor
    extends WorkflowOutboundCallsInterceptorBase {

  private final InterceptorTracer tracer;

  public OpenTelemetryWorkflowOutboundCallsInterceptor(
      InterceptorTracer tracer, WorkflowOutboundCallsInterceptor next) {
    super(next);
    this.tracer = tracer;
  }

  /**
   * Makes the context of the calling workflow code current while a callback runs. The SDK runs
   * callbacks registered on an incomplete promise in a separate workflow thread, which would
   * otherwise see the workflow's root context.
   */
  private static class ContextPromise<R> implements Promise<R> {
    private final Promise<R> delegate;

    ContextPromise(Promise<R> delegate) {
      this.delegate = delegate;
    }

    private static <O> O wrap(Context context, Functions.Func<O> fn) {
      try (Scope ignored = context.makeCurrent()) {
        return fn.apply();
      }
    }

    @Override
    public boolean isCompleted() {
      return delegate.isCompleted();
    }

    @Override
    public R get() {
      return delegate.get();
    }

    @Override
    public R cancellableGet() {
      return delegate.cancellableGet();
    }

    @Override
    public R get(long timeout, TimeUnit unit) throws TimeoutException {
      return delegate.get(timeout, unit);
    }

    @Override
    public R cancellableGet(long timeout, TimeUnit unit) throws TimeoutException {
      return delegate.cancellableGet(timeout, unit);
    }

    @Override
    public RuntimeException getFailure() {
      return delegate.getFailure();
    }

    @Override
    public <U> Promise<U> thenApply(Functions.Func1<? super R, ? extends U> fn) {
      Context context = Context.current();
      return new ContextPromise<>(delegate.thenApply((r) -> wrap(context, () -> fn.apply(r))));
    }

    @Override
    public <U> Promise<U> handle(Functions.Func2<? super R, RuntimeException, ? extends U> fn) {
      Context context = Context.current();
      return new ContextPromise<>(delegate.handle((r, e) -> wrap(context, () -> fn.apply(r, e))));
    }

    @Override
    public <U> Promise<U> thenCompose(Functions.Func1<? super R, ? extends Promise<U>> fn) {
      Context context = Context.current();
      return new ContextPromise<>(delegate.thenCompose((r) -> wrap(context, () -> fn.apply(r))));
    }

    @Override
    public Promise<R> exceptionally(Functions.Func1<Throwable, ? extends R> fn) {
      Context context = Context.current();
      return new ContextPromise<>(delegate.exceptionally((t) -> wrap(context, () -> fn.apply(t))));
    }
  }

  @Override
  public <R> ActivityOutput<R> executeActivity(ActivityInput<R> input) {
    ActivityOutput<R> output =
        tracer.traceOutbound(
            "StartActivity",
            input.getActivityName(),
            activityTags(input.getActivityId()),
            input.getHeader(),
            () -> super.executeActivity(input));
    return new ActivityOutput<>(output.getActivityId(), new ContextPromise<>(output.getResult()));
  }

  @Override
  public <R> LocalActivityOutput<R> executeLocalActivity(LocalActivityInput<R> input) {
    LocalActivityOutput<R> output =
        tracer.traceOutbound(
            "StartActivity",
            input.getActivityName(),
            activityTags(input.getActivityId()),
            input.getHeader(),
            () -> super.executeLocalActivity(input));
    return new LocalActivityOutput<>(new ContextPromise<>(output.getResult()));
  }

  @Override
  public <R> ChildWorkflowOutput<R> executeChildWorkflow(ChildWorkflowInput<R> input) {
    ChildWorkflowOutput<R> output =
        tracer.traceOutbound(
            "StartChildWorkflow",
            input.getWorkflowType(),
            childWorkflowTags(input),
            input.getHeader(),
            () -> super.executeChildWorkflow(input));
    return new ChildWorkflowOutput<>(
        new ContextPromise<>(output.getResult()),
        new ContextPromise<>(output.getWorkflowExecution()));
  }

  @Override
  public <R> ExecuteNexusOperationOutput<R> executeNexusOperation(
      ExecuteNexusOperationInput<R> input) {
    ExecuteNexusOperationOutput<R> output =
        tracer.traceNexusOutbound(
            "StartNexusOperation",
            input.getService() + "/" + input.getOperation(),
            nexusTags(input),
            input.getHeaders(),
            () -> super.executeNexusOperation(input));
    return new ExecuteNexusOperationOutput<>(
        new ContextPromise<>(output.getResult()),
        new ContextPromise<>(output.getOperationExecution()));
  }

  @Override
  public Promise<Void> newTimer(Duration duration) {
    return new ContextPromise<>(super.newTimer(duration));
  }

  @Override
  public Promise<Void> newTimer(Duration duration, TimerOptions options) {
    return new ContextPromise<>(super.newTimer(duration, options));
  }

  @Override
  public SignalExternalOutput signalExternalWorkflow(SignalExternalInput input) {
    SignalExternalOutput output =
        tracer.traceOutbound(
            "SignalExternalWorkflow",
            input.getSignalName(),
            workflowExecutionTags(input.getExecution()),
            input.getHeader(),
            () -> super.signalExternalWorkflow(input));
    return new SignalExternalOutput(new ContextPromise<>(output.getResult()));
  }

  @Override
  public CancelWorkflowOutput cancelWorkflow(CancelWorkflowInput input) {
    CancelWorkflowOutput output =
        tracer.traceOutbound(
            "CancelWorkflow",
            "",
            workflowExecutionTags(input.getExecution()),
            () -> super.cancelWorkflow(input));
    return new CancelWorkflowOutput(new ContextPromise<>(output.getResult()));
  }

  @Override
  public void continueAsNew(ContinueAsNewInput input) {
    String workflowType = input.getWorkflowType();
    if (workflowType == null) {
      workflowType = Workflow.getInfo().getWorkflowType();
    }
    tracer.traceOutbound(
        "ContinueAsNew",
        workflowType,
        workflowTags(),
        input.getHeader(),
        () -> super.continueAsNew(input));
  }

  private static Attributes workflowTags() {
    WorkflowInfo info = Workflow.getInfo();
    return Attributes.of(WORKFLOW_ID, info.getWorkflowId(), RUN_ID, info.getRunId());
  }

  private static Attributes activityTags(@Nullable String activityId) {
    Attributes tags = workflowTags();
    if (activityId == null) {
      return tags;
    }
    return tags.toBuilder().put(ACTIVITY_ID, activityId).build();
  }

  private static Attributes workflowExecutionTags(WorkflowExecution execution) {
    return Attributes.of(WORKFLOW_ID, execution.getWorkflowId(), RUN_ID, execution.getRunId());
  }

  private static Attributes childWorkflowTags(ChildWorkflowInput<?> input) {
    return Attributes.of(WORKFLOW_ID, input.getWorkflowId());
  }

  private static Attributes nexusTags(ExecuteNexusOperationInput<?> input) {
    AttributesBuilder tags =
        workflowTags().toBuilder()
            .put(NEXUS_SERVICE, input.getService())
            .put(NEXUS_OPERATION, input.getOperation())
            .put(NEXUS_ENDPOINT, input.getEndpoint());

    return tags.build();
  }
}
