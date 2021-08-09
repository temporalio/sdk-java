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
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptorBase;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.opentracing.SpanOperationType;

public class OpenTracingWorkflowClientCallsInterceptor extends WorkflowClientCallsInterceptorBase {
  private final SpanFactory spanFactory;
  private final Tracer tracer;
  private final ContextAccessor contextAccessor;

  public OpenTracingWorkflowClientCallsInterceptor(
      WorkflowClientCallsInterceptor next,
      OpenTracingOptions options,
      SpanFactory spanFactory,
      ContextAccessor contextAccessor) {
    super(next);
    this.spanFactory = spanFactory;
    this.tracer = options.getTracer();
    this.contextAccessor = contextAccessor;
  }

  @Override
  public WorkflowStartOutput start(WorkflowStartInput input) {
    Span span = createAndPassWorkflowStartSpan(input, SpanOperationType.START_WORKFLOW);
    try {
      return super.start(input);
    } finally {
      span.finish();
    }
  }

  @Override
  public WorkflowSignalWithStartOutput signalWithStart(WorkflowSignalWithStartInput input) {
    Span workflowStartSpan =
        createAndPassWorkflowStartSpan(
            input.getWorkflowStartInput(), SpanOperationType.SIGNAL_WITH_START_WORKFLOW);
    try (Scope scope = tracer.scopeManager().activate(workflowStartSpan)) {
      return super.signalWithStart(input);
    } finally {
      workflowStartSpan.finish();
    }
  }

  private Span createAndPassWorkflowStartSpan(
      WorkflowStartInput input, SpanOperationType operationType) {
    Span span = createWorkflowStartSpanBuilder(input, operationType).start();
    contextAccessor.writeSpanContextToHeader(span.context(), input.getHeader(), tracer);
    return span;
  }

  private Tracer.SpanBuilder createWorkflowStartSpanBuilder(
      WorkflowStartInput input, SpanOperationType operationType) {
    return spanFactory.createWorkflowStartSpan(
        tracer,
        operationType,
        input.getWorkflowType(),
        System.currentTimeMillis(),
        input.getWorkflowId());
  }
}
