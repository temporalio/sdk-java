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

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptorBase;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInfo;

public class OpenTracingWorkflowOutboundCallsInterceptor
    extends WorkflowOutboundCallsInterceptorBase {
  private final SpanFactory spanFactory;
  private final Tracer tracer;

  public OpenTracingWorkflowOutboundCallsInterceptor(
      WorkflowOutboundCallsInterceptor next, OpenTracingOptions options, SpanFactory spanFactory) {
    super(next);
    this.spanFactory = spanFactory;
    this.tracer = options.getTracer();
  }

  @Override
  public <R> ActivityOutput<R> executeActivity(ActivityInput<R> input) {
    if (!Workflow.isReplaying()) {
      Span activityStartSpan =
          createAndPassActivityStartSpan(input.getActivityName(), input.getHeader());
      try (Scope scope = tracer.scopeManager().activate(activityStartSpan)) {
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
          createAndPassActivityStartSpan(input.getActivityName(), input.getHeader());
      try (Scope scope = tracer.scopeManager().activate(activityStartSpan)) {
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
      Span childWorkflowStartSpan = createAndPassChildWorkflowStartSpan(input);
      try (Scope scope = tracer.scopeManager().activate(childWorkflowStartSpan)) {
        return super.executeChildWorkflow(input);
      } finally {
        childWorkflowStartSpan.finish();
      }
    } else {
      return super.executeChildWorkflow(input);
    }
  }

  private Span createAndPassActivityStartSpan(String activityName, Header header) {
    Span span = createActivityStartSpanBuilder(activityName).start();
    OpenTracingContextAccessor.writeSpanContextToHeader(span.context(), header, tracer);
    return span;
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

  private <R> Span createAndPassChildWorkflowStartSpan(ChildWorkflowInput<R> input) {
    Span span = createChildWorkflowStartSpanBuilder(tracer, input).start();
    OpenTracingContextAccessor.writeSpanContextToHeader(span.context(), input.getHeader(), tracer);
    return span;
  }

  private <R> Tracer.SpanBuilder createChildWorkflowStartSpanBuilder(
      Tracer tracer, ChildWorkflowInput<R> input) {
    WorkflowInfo parentWorkflowInfo = Workflow.getInfo();
    return spanFactory.createChildWorkflowStartSpan(
        tracer,
        input.getWorkflowType(),
        System.currentTimeMillis(),
        input.getWorkflowId(),
        parentWorkflowInfo.getWorkflowId(),
        parentWorkflowInfo.getRunId());
  }
}
