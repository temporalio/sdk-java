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

import io.nexusrpc.OperationUnsuccessfulException;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.temporal.common.interceptors.NexusInboundCallsInterceptor;
import io.temporal.common.interceptors.NexusInboundCallsInterceptorBase;
import io.temporal.opentracing.OpenTracingOptions;

public class OpenTracingNexusInboundCallsInterceptor extends NexusInboundCallsInterceptorBase {
  private final OpenTracingOptions options;
  private final SpanFactory spanFactory;
  private final Tracer tracer;
  private final ContextAccessor contextAccessor;

  public OpenTracingNexusInboundCallsInterceptor(
      NexusInboundCallsInterceptor next,
      OpenTracingOptions options,
      SpanFactory spanFactory,
      ContextAccessor contextAccessor) {
    super(next);
    this.options = options;
    this.spanFactory = spanFactory;
    this.tracer = options.getTracer();
    this.contextAccessor = contextAccessor;
  }

  @Override
  public StartOperationOutput startOperation(StartOperationInput input)
      throws OperationUnsuccessfulException {
    SpanContext rootSpanContext =
        contextAccessor.readSpanContextFromHeader(input.getOperationContext().getHeaders(), tracer);

    Span operationStartSpan =
        spanFactory
            .createStartNexusOperationSpan(
                tracer,
                input.getOperationContext().getService(),
                input.getOperationContext().getOperation(),
                rootSpanContext)
            .start();
    try (Scope scope = tracer.scopeManager().activate(operationStartSpan)) {
      return super.startOperation(input);
    } catch (Throwable t) {
      spanFactory.logFail(operationStartSpan, t);
      throw t;
    } finally {
      operationStartSpan.finish();
    }
  }

  @Override
  public CancelOperationOutput cancelOperation(CancelOperationInput input) {
    SpanContext rootSpanContext =
        contextAccessor.readSpanContextFromHeader(input.getOperationContext().getHeaders(), tracer);

    Span operationCancelSpan =
        spanFactory
            .createCancelNexusOperationSpan(
                tracer,
                input.getOperationContext().getService(),
                input.getOperationContext().getOperation(),
                rootSpanContext)
            .start();
    try (Scope scope = tracer.scopeManager().activate(operationCancelSpan)) {
      return super.cancelOperation(input);
    } catch (Throwable t) {
      spanFactory.logFail(operationCancelSpan, t);
      throw t;
    } finally {
      operationCancelSpan.finish();
    }
  }
}
