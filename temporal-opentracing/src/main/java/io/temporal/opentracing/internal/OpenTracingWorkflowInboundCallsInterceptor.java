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
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.internal.sync.DestroyWorkflowThreadError;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.workflow.Workflow;

public class OpenTracingWorkflowInboundCallsInterceptor
    extends WorkflowInboundCallsInterceptorBase {
  private final OpenTracingOptions options;
  private final SpanFactory spanFactory;
  private final ContextAccessor contextAccessor;

  public OpenTracingWorkflowInboundCallsInterceptor(
      WorkflowInboundCallsInterceptor next,
      OpenTracingOptions options,
      SpanFactory spanFactory,
      ContextAccessor contextAccessor) {
    super(next);
    this.options = options;
    this.spanFactory = spanFactory;
    this.contextAccessor = contextAccessor;
  }

  @Override
  public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
    super.init(
        new OpenTracingWorkflowOutboundCallsInterceptor(
            outboundCalls, options, spanFactory, contextAccessor));
  }

  @Override
  public WorkflowOutput execute(WorkflowInput input) {
    Tracer tracer = options.getTracer();
    SpanContext rootSpanContext =
        contextAccessor.readSpanContextFromHeader(input.getHeader(), tracer);
    Span workflowRunSpan =
        spanFactory
            .createWorkflowRunSpan(
                tracer,
                Workflow.getInfo().getWorkflowType(),
                Workflow.currentTimeMillis(),
                Workflow.getInfo().getWorkflowId(),
                Workflow.getInfo().getRunId(),
                rootSpanContext)
            .start();
    try (Scope scope = tracer.scopeManager().activate(workflowRunSpan)) {
      return super.execute(input);
    } catch (Throwable t) {
      if (t instanceof DestroyWorkflowThreadError) {
        spanFactory.logEviction(workflowRunSpan);
      } else {
        spanFactory.logFail(workflowRunSpan, t);
      }
      throw t;
    } finally {
      workflowRunSpan.finish();
    }
  }
}
