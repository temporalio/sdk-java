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

import io.opentracing.References;
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
    Span workflowStartSpan =
        contextAccessor.writeSpanContextToHeader(
            () -> createWorkflowStartSpanBuilder(input, SpanOperationType.START_WORKFLOW).start(),
            input.getHeader(),
            tracer);
    try (Scope ignored = tracer.scopeManager().activate(workflowStartSpan)) {
      return super.start(input);
    } finally {
      workflowStartSpan.finish();
    }
  }

  @Override
  public WorkflowSignalOutput signal(WorkflowSignalInput input) {
    Span workflowSignalSpan =
        contextAccessor.writeSpanContextToHeader(
            () ->
                spanFactory
                    .createWorkflowSignalSpan(
                        tracer,
                        input.getSignalName(),
                        input.getWorkflowExecution().getWorkflowId(),
                        References.FOLLOWS_FROM)
                    .start(),
            input.getHeader(),
            tracer);
    try (Scope ignored = tracer.scopeManager().activate(workflowSignalSpan)) {
      return super.signal(input);
    } finally {
      workflowSignalSpan.finish();
    }
  }

  @Override
  public WorkflowSignalWithStartOutput signalWithStart(WorkflowSignalWithStartInput input) {
    WorkflowStartInput workflowStartInput = input.getWorkflowStartInput();
    Span workflowStartSpan =
        contextAccessor.writeSpanContextToHeader(
            () ->
                createWorkflowStartSpanBuilder(
                        workflowStartInput, SpanOperationType.SIGNAL_WITH_START_WORKFLOW)
                    .start(),
            workflowStartInput.getHeader(),
            tracer);
    try (Scope ignored = tracer.scopeManager().activate(workflowStartSpan)) {
      return super.signalWithStart(input);
    } finally {
      workflowStartSpan.finish();
    }
  }

  @Override
  public <R> QueryOutput<R> query(QueryInput<R> input) {
    Span workflowQuerySpan =
        contextAccessor.writeSpanContextToHeader(
            () ->
                spanFactory
                    .createWorkflowQuerySpan(
                        tracer,
                        input.getQueryType(),
                        input.getWorkflowExecution().getWorkflowId(),
                        References.FOLLOWS_FROM)
                    .start(),
            input.getHeader(),
            tracer);
    try (Scope ignored = tracer.scopeManager().activate(workflowQuerySpan)) {
      return super.query(input);
    } finally {
      workflowQuerySpan.finish();
    }
  }

  @Override
  public <R> StartUpdateOutput<R> startUpdate(StartUpdateInput<R> input) {
    Span workflowStartUpdateSpan =
        contextAccessor.writeSpanContextToHeader(
            () ->
                spanFactory
                    .createWorkflowStartUpdateSpan(
                        tracer,
                        input.getUpdateName(),
                        input.getWorkflowExecution().getWorkflowId(),
                        References.FOLLOWS_FROM)
                    .start(),
            input.getHeader(),
            tracer);
    try (Scope ignored = tracer.scopeManager().activate(workflowStartUpdateSpan)) {
      return super.startUpdate(input);
    } finally {
      workflowStartUpdateSpan.finish();
    }
  }

  private Tracer.SpanBuilder createWorkflowStartSpanBuilder(
      WorkflowStartInput input, SpanOperationType operationType) {
    return spanFactory.createWorkflowStartSpan(
        tracer, operationType, input.getWorkflowType(), input.getWorkflowId());
  }
}
