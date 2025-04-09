/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.opentracing.internal;

import com.google.common.base.MoreObjects;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptorBase;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInfo;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class OpenTracingWorkflowOutboundCallsInterceptor
    extends WorkflowOutboundCallsInterceptorBase {
  private final SpanFactory spanFactory;
  private final Tracer tracer;
  private final ContextAccessor contextAccessor;

  private class PromiseWrapper<R> implements Promise<R> {
    private final Span capturedSpan;
    private final Promise<R> delegate;

    PromiseWrapper(Span capturedSpan, Promise<R> delegate) {
      this.capturedSpan = capturedSpan;
      this.delegate = delegate;
    }

    private <O> O wrap(Functions.Func<O> fn) {
      Span activeSpan = tracer.scopeManager().activeSpan();
      if (activeSpan == null && capturedSpan != null) {
        try (Scope ignored = tracer.scopeManager().activate(capturedSpan)) {
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

  public OpenTracingWorkflowOutboundCallsInterceptor(
      WorkflowOutboundCallsInterceptor next,
      OpenTracingOptions options,
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
      Span capturedSpan = tracer.scopeManager().activeSpan();
      Span activityStartSpan =
          contextAccessor.writeSpanContextToHeader(
              () -> createActivityStartSpanBuilder(input.getActivityName()).start(),
              input.getHeader(),
              tracer);
      try (Scope ignored = tracer.scopeManager().activate(activityStartSpan)) {
        ActivityOutput<R> output = super.executeActivity(input);
        return new ActivityOutput<>(
            output.getActivityId(), new PromiseWrapper<>(capturedSpan, output.getResult()));
      } finally {
        activityStartSpan.finish();
      }
    } else {
      return super.executeActivity(input);
    }
  }

  @Override
  public <R> LocalActivityOutput<R> executeLocalActivity(LocalActivityInput<R> input) {
    if (!WorkflowUnsafe.isReplaying()) {
      Span capturedSpan = tracer.scopeManager().activeSpan();
      Span activityStartSpan =
          contextAccessor.writeSpanContextToHeader(
              () -> createActivityStartSpanBuilder(input.getActivityName()).start(),
              input.getHeader(),
              tracer);
      try (Scope ignored = tracer.scopeManager().activate(activityStartSpan)) {
        LocalActivityOutput<R> output = super.executeLocalActivity(input);
        return new LocalActivityOutput<>(new PromiseWrapper<>(capturedSpan, output.getResult()));
      } finally {
        activityStartSpan.finish();
      }
    } else {
      return super.executeLocalActivity(input);
    }
  }

  @Override
  public <R> ChildWorkflowOutput<R> executeChildWorkflow(ChildWorkflowInput<R> input) {
    if (!WorkflowUnsafe.isReplaying()) {
      Span capturedSpan = tracer.scopeManager().activeSpan();
      Span childWorkflowStartSpan =
          contextAccessor.writeSpanContextToHeader(
              () -> createChildWorkflowStartSpanBuilder(input).start(), input.getHeader(), tracer);
      try (Scope ignored = tracer.scopeManager().activate(childWorkflowStartSpan)) {
        ChildWorkflowOutput<R> output = super.executeChildWorkflow(input);
        return new ChildWorkflowOutput<>(
            new PromiseWrapper<>(capturedSpan, output.getResult()),
            new PromiseWrapper<>(capturedSpan, output.getWorkflowExecution()));
      } finally {
        childWorkflowStartSpan.finish();
      }
    } else {
      return super.executeChildWorkflow(input);
    }
  }

  @Override
  public <R> ExecuteNexusOperationOutput<R> executeNexusOperation(
      ExecuteNexusOperationInput<R> input) {
    if (!WorkflowUnsafe.isReplaying()) {
      Span capturedSpan = tracer.scopeManager().activeSpan();
      Span nexusOperationExecuteSpan =
          contextAccessor.writeSpanContextToHeader(
              () -> createStartNexusOperationSpanBuilder(input).start(),
              input.getHeaders(),
              tracer);
      try (Scope ignored = tracer.scopeManager().activate(nexusOperationExecuteSpan)) {
        ExecuteNexusOperationOutput<R> output = super.executeNexusOperation(input);
        return new ExecuteNexusOperationOutput<>(
            new PromiseWrapper<>(capturedSpan, output.getResult()),
            new PromiseWrapper<>(capturedSpan, output.getOperationExecution()));
      } finally {
        nexusOperationExecuteSpan.finish();
      }
    } else {
      return super.executeNexusOperation(input);
    }
  }

  @Override
  public SignalExternalOutput signalExternalWorkflow(SignalExternalInput input) {
    if (!WorkflowUnsafe.isReplaying()) {
      Span capturedSpan = tracer.scopeManager().activeSpan();
      WorkflowInfo workflowInfo = Workflow.getInfo();
      Span childWorkflowStartSpan =
          contextAccessor.writeSpanContextToHeader(
              () ->
                  spanFactory
                      .createExternalWorkflowSignalSpan(
                          tracer,
                          input.getSignalName(),
                          workflowInfo.getWorkflowId(),
                          workflowInfo.getRunId())
                      .start(),
              input.getHeader(),
              tracer);
      try (Scope ignored = tracer.scopeManager().activate(childWorkflowStartSpan)) {
        SignalExternalOutput output = super.signalExternalWorkflow(input);
        return new SignalExternalOutput(new PromiseWrapper<>(capturedSpan, output.getResult()));
      } finally {
        childWorkflowStartSpan.finish();
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
              () -> createContinueAsNewWorkflowStartSpanBuilder(input).start(),
              input.getHeader(),
              tracer);
      try (Scope ignored = tracer.scopeManager().activate(continueAsNewStartSpan)) {
        super.continueAsNew(input);
      } finally {
        continueAsNewStartSpan.finish();
      }
    } else {
      super.continueAsNew(input);
    }
  }

  @Override
  public Object newChildThread(Runnable runnable, boolean detached, String name) {
    Span activeSpan = tracer.scopeManager().activeSpan();
    Runnable wrappedRunnable =
        activeSpan != null
            ? () -> {
              // transfer the existing active span into another thread
              try (Scope ignored = tracer.scopeManager().activate(activeSpan)) {
                runnable.run();
              }
            }
            : runnable;
    return super.newChildThread(wrappedRunnable, detached, name);
  }

  private Tracer.SpanBuilder createActivityStartSpanBuilder(String activityName) {
    WorkflowInfo workflowInfo = Workflow.getInfo();
    return spanFactory.createActivityStartSpan(
        tracer, activityName, workflowInfo.getWorkflowId(), workflowInfo.getRunId());
  }

  private <R> Tracer.SpanBuilder createChildWorkflowStartSpanBuilder(ChildWorkflowInput<R> input) {
    WorkflowInfo parentWorkflowInfo = Workflow.getInfo();
    return spanFactory.createChildWorkflowStartSpan(
        tracer,
        input.getWorkflowType(),
        input.getWorkflowId(),
        Workflow.currentTimeMillis(),
        parentWorkflowInfo.getWorkflowId(),
        parentWorkflowInfo.getRunId());
  }

  private <R> Tracer.SpanBuilder createStartNexusOperationSpanBuilder(
      ExecuteNexusOperationInput<R> input) {
    WorkflowInfo parentWorkflowInfo = Workflow.getInfo();
    return spanFactory.createStartNexusOperationSpan(
        tracer,
        input.getService(),
        input.getOperation(),
        parentWorkflowInfo.getWorkflowId(),
        parentWorkflowInfo.getRunId());
  }

  private Tracer.SpanBuilder createContinueAsNewWorkflowStartSpanBuilder(ContinueAsNewInput input) {
    WorkflowInfo continuedWorkflowInfo = Workflow.getInfo();
    return spanFactory.createContinueAsNewWorkflowStartSpan(
        tracer,
        MoreObjects.firstNonNull(input.getWorkflowType(), continuedWorkflowInfo.getWorkflowType()),
        continuedWorkflowInfo.getWorkflowId(),
        continuedWorkflowInfo.getRunId());
  }
}
