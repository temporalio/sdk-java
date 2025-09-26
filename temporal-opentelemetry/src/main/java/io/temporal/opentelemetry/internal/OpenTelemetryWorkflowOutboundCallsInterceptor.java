package io.temporal.opentelemetry.internal;

import com.google.common.base.MoreObjects;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.context.Scope;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptorBase;
import io.temporal.opentelemetry.OpenTelemetryOptions;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInfo;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class OpenTelemetryWorkflowOutboundCallsInterceptor
    extends WorkflowOutboundCallsInterceptorBase {

  private final SpanFactory spanFactory;
  private final io.opentelemetry.api.trace.Tracer tracer;
  private final ContextAccessor contextAccessor;

  private class PromiseWrapper<R> implements Promise<R> {
    private final Span capturedSpan;
    private final Promise<R> delegate;

    PromiseWrapper(Span capturedSpan, Promise<R> delegate) {
      this.capturedSpan = capturedSpan;
      this.delegate = delegate;
    }

    private <O> O wrap(Functions.Func<O> fn) {
      Span activeSpan = Span.current();
      if (activeSpan == null && capturedSpan != null) {
        try (Scope ignored = capturedSpan.makeCurrent()) {
          return fn.apply();
        }
      } else {
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
      return delegate.thenApply((r) -> wrap(() -> fn.apply(r)));
    }

    @Override
    public <U> Promise<U> handle(Functions.Func2<? super R, RuntimeException, ? extends U> fn) {
      return delegate.handle((r, e) -> wrap(() -> fn.apply(r, e)));
    }

    @Override
    public <U> Promise<U> thenCompose(Functions.Func1<? super R, ? extends Promise<U>> fn) {
      return delegate.thenCompose((r) -> wrap(() -> fn.apply(r)));
    }

    @Override
    public Promise<R> exceptionally(Functions.Func1<Throwable, ? extends R> fn) {
      return delegate.exceptionally((t) -> wrap(() -> fn.apply(t)));
    }
  }

  public OpenTelemetryWorkflowOutboundCallsInterceptor(
      WorkflowOutboundCallsInterceptor next,
      OpenTelemetryOptions options,
      SpanFactory spanFactory,
      ContextAccessor contextAccessor) {
    super(next);
    this.spanFactory = spanFactory;
    this.tracer = options.getTracer();
    this.contextAccessor = contextAccessor;
  }

  @Override
  public <R> ActivityOutput<R> executeActivity(ActivityInput<R> input) {
    if (!WorkflowUnsafe.isReplaying()) {
      Span capturedSpan = Span.current();
      Span activityStartSpan =
          contextAccessor.writeSpanContextToHeader(
              () -> createActivityStartSpanBuilder(input.getActivityName()).startSpan(),
              input.getHeader(),
              tracer);
      try (Scope ignored = activityStartSpan.makeCurrent()) {
        ActivityOutput<R> output = super.executeActivity(input);
        return new ActivityOutput<>(
            output.getActivityId(), new PromiseWrapper<>(capturedSpan, output.getResult()));
      } finally {
        activityStartSpan.end();
      }
    } else {
      return super.executeActivity(input);
    }
  }

  @Override
  public <R> LocalActivityOutput<R> executeLocalActivity(LocalActivityInput<R> input) {
    if (!WorkflowUnsafe.isReplaying()) {
      Span capturedSpan = Span.current();
      Span activityStartSpan =
          contextAccessor.writeSpanContextToHeader(
              () -> createActivityStartSpanBuilder(input.getActivityName()).startSpan(),
              input.getHeader(),
              tracer);
      try (Scope ignored = activityStartSpan.makeCurrent()) {
        LocalActivityOutput<R> output = super.executeLocalActivity(input);
        return new LocalActivityOutput<>(new PromiseWrapper<>(capturedSpan, output.getResult()));
      } finally {
        activityStartSpan.end();
      }
    } else {
      return super.executeLocalActivity(input);
    }
  }

  @Override
  public <R> ChildWorkflowOutput<R> executeChildWorkflow(ChildWorkflowInput<R> input) {
    if (!WorkflowUnsafe.isReplaying()) {
      Span capturedSpan = Span.current();
      Span childWorkflowStartSpan =
          contextAccessor.writeSpanContextToHeader(
              () -> createChildWorkflowStartSpanBuilder(input).startSpan(),
              input.getHeader(),
              tracer);
      try (Scope ignored = childWorkflowStartSpan.makeCurrent()) {
        ChildWorkflowOutput<R> output = super.executeChildWorkflow(input);
        return new ChildWorkflowOutput<>(
            new PromiseWrapper<>(capturedSpan, output.getResult()),
            new PromiseWrapper<>(capturedSpan, output.getWorkflowExecution()));
      } finally {
        childWorkflowStartSpan.end();
      }
    } else {
      return super.executeChildWorkflow(input);
    }
  }

  @Override
  public SignalExternalOutput signalExternalWorkflow(SignalExternalInput input) {
    if (!WorkflowUnsafe.isReplaying()) {
      Span capturedSpan = Span.current();
      WorkflowInfo workflowInfo = Workflow.getInfo();
      Span signalExternalSpan =
          contextAccessor.writeSpanContextToHeader(
              () ->
                  spanFactory
                      .createExternalWorkflowSignalSpan(
                          tracer,
                          input.getSignalName(),
                          workflowInfo.getWorkflowId(),
                          workflowInfo.getRunId())
                      .startSpan(),
              input.getHeader(),
              tracer);
      try (Scope ignored = signalExternalSpan.makeCurrent()) {
        SignalExternalOutput output = super.signalExternalWorkflow(input);
        return new SignalExternalOutput(new PromiseWrapper<>(capturedSpan, output.getResult()));
      } finally {
        signalExternalSpan.end();
      }
    } else {
      return super.signalExternalWorkflow(input);
    }
  }

  @Override
  public void continueAsNew(ContinueAsNewInput input) {
    if (!WorkflowUnsafe.isReplaying()) {
      Span continueAsNewStartSpan =
          contextAccessor.writeSpanContextToHeader(
              () -> createContinueAsNewWorkflowStartSpanBuilder(input).startSpan(),
              input.getHeader(),
              tracer);
      try (Scope ignored = continueAsNewStartSpan.makeCurrent()) {
        super.continueAsNew(input);
      } finally {
        continueAsNewStartSpan.end();
      }
    } else {
      super.continueAsNew(input);
    }
  }

  @Override
  public Object newChildThread(Runnable runnable, boolean detached, String name) {
    // Capture the current context which includes both the span and any baggage
    io.opentelemetry.context.Context currentContext = io.opentelemetry.context.Context.current();

    Runnable wrappedRunnable =
        () -> {
          // Transfer the existing context (span and baggage) into the new thread
          try (Scope ignored = currentContext.makeCurrent()) {
            runnable.run();
          }
        };
    return super.newChildThread(wrappedRunnable, detached, name);
  }

  private SpanBuilder createActivityStartSpanBuilder(String activityName) {
    WorkflowInfo workflowInfo = Workflow.getInfo();
    return spanFactory.createActivityStartSpan(
        tracer, activityName, workflowInfo.getWorkflowId(), workflowInfo.getRunId());
  }

  private <R> SpanBuilder createChildWorkflowStartSpanBuilder(ChildWorkflowInput<R> input) {
    WorkflowInfo parentWorkflowInfo = Workflow.getInfo();
    return spanFactory.createChildWorkflowStartSpan(
        tracer,
        input.getWorkflowType(),
        input.getWorkflowId(),
        Workflow.currentTimeMillis(),
        parentWorkflowInfo.getWorkflowId(),
        parentWorkflowInfo.getRunId());
  }

  private SpanBuilder createContinueAsNewWorkflowStartSpanBuilder(ContinueAsNewInput input) {
    WorkflowInfo continuedWorkflowInfo = Workflow.getInfo();
    return spanFactory.createContinueAsNewWorkflowStartSpan(
        tracer,
        MoreObjects.firstNonNull(input.getWorkflowType(), continuedWorkflowInfo.getWorkflowType()),
        continuedWorkflowInfo.getWorkflowId(),
        continuedWorkflowInfo.getRunId());
  }
}
