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

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInfo;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptorBase;
import io.temporal.opentracing.OpenTracingOptions;

public class OpenTracingActivityInboundCallsInterceptor
    extends ActivityInboundCallsInterceptorBase {
  private final OpenTracingOptions options;
  private final SpanFactory spanFactory;
  private final Tracer tracer;
  private final ContextAccessor contextAccessor;

  public OpenTracingActivityInboundCallsInterceptor(
      ActivityInboundCallsInterceptor next,
      OpenTracingOptions options,
      SpanFactory spanFactory,
      ContextAccessor contextAccessor) {
    super(next);
    this.options = options;
    this.spanFactory = spanFactory;
    this.tracer = options.getTracer();
    this.contextAccessor = contextAccessor;
  }

  private ActivityExecutionContext activityExecutionContext;

  @Override
  public void init(ActivityExecutionContext context) {
    // Workflow Interceptors have access to Workflow.getInfo methods,
    // but Activity Interceptors don't have access to Activity.getExecutionContext().getInfo()
    // This is inconsistent and should be addressed, but this is a workaround.
    this.activityExecutionContext = context;
    super.init(context);
  }

  @Override
  public ActivityOutput execute(ActivityInput input) {
    SpanContext rootSpanContext =
        contextAccessor.readSpanContextFromHeader(input.getHeader(), tracer);
    ActivityInfo activityInfo = activityExecutionContext.getInfo();
    Span activityRunSpan =
        spanFactory
            .createActivityRunSpan(
                tracer,
                activityInfo.getActivityType(),
                activityInfo.getWorkflowId(),
                activityInfo.getRunId(),
                rootSpanContext)
            .start();
    try (Scope scope = tracer.scopeManager().activate(activityRunSpan)) {
      return super.execute(input);
    } catch (Throwable t) {
      spanFactory.logFail(activityRunSpan, t);
      throw t;
    } finally {
      activityRunSpan.finish();
    }
  }
}
