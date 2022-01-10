/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.opentracing.internal;

import com.google.common.base.MoreObjects;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptorBase;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInfo;

public class OpenTracingWorkflowOutboundCallsInterceptor
    extends WorkflowOutboundCallsInterceptorBase {
  private final SpanFactory spanFactory;
  private final Tracer tracer;
  private final ContextAccessor contextAccessor;

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
    if (!Workflow.isReplaying()) {
      Span activityStartSpan =
          contextAccessor.writeSpanContextToHeader(
              () -> createActivityStartSpanBuilder(input.getActivityName()).start(),
              input.getHeader(),
              tracer);
      try (Scope ignored = tracer.scopeManager().activate(activityStartSpan)) {
        return super.executeActivity(input);
      } finally {
        activityStartSpan.finish();
      }
    } else {
      return super.executeActivity(input);
    }
  }

  @Override
  public <R> LocalActivityOutput<R> executeLocalActivity(LocalActivityInput<R> input) {
    if (!Workflow.isReplaying()) {
      Span activityStartSpan =
          contextAccessor.writeSpanContextToHeader(
              () -> createActivityStartSpanBuilder(input.getActivityName()).start(),
              input.getHeader(),
              tracer);
      try (Scope ignored = tracer.scopeManager().activate(activityStartSpan)) {
        return super.executeLocalActivity(input);
      } finally {
        activityStartSpan.finish();
      }
    } else {
      return super.executeLocalActivity(input);
    }
  }

  @Override
  public <R> ChildWorkflowOutput<R> executeChildWorkflow(ChildWorkflowInput<R> input) {
    if (!Workflow.isReplaying()) {
      Span childWorkflowStartSpan =
          contextAccessor.writeSpanContextToHeader(
              () -> createChildWorkflowStartSpanBuilder(input).start(), input.getHeader(), tracer);
      try (Scope ignored = tracer.scopeManager().activate(childWorkflowStartSpan)) {
        return super.executeChildWorkflow(input);
      } finally {
        childWorkflowStartSpan.finish();
      }
    } else {
      return super.executeChildWorkflow(input);
    }
  }

  @Override
  public void continueAsNew(ContinueAsNewInput input) {
    if (!Workflow.isReplaying()) {
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
        tracer,
        activityName,
        Workflow.currentTimeMillis(),
        workflowInfo.getWorkflowId(),
        workflowInfo.getRunId());
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

  private Tracer.SpanBuilder createContinueAsNewWorkflowStartSpanBuilder(ContinueAsNewInput input) {
    WorkflowInfo parentWorkflowInfo = Workflow.getInfo();
    return spanFactory.createContinueAsNewWorkflowStartSpan(
        tracer,
        MoreObjects.firstNonNull(input.getWorkflowType(), parentWorkflowInfo.getWorkflowType()),
        parentWorkflowInfo.getWorkflowId(),
        Workflow.currentTimeMillis(),
        parentWorkflowInfo.getRunId());
  }
}
