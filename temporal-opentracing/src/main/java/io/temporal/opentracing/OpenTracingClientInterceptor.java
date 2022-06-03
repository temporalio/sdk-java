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

package io.temporal.opentracing;

import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptorBase;
import io.temporal.opentracing.internal.ContextAccessor;
import io.temporal.opentracing.internal.OpenTracingWorkflowClientCallsInterceptor;
import io.temporal.opentracing.internal.SpanFactory;

public class OpenTracingClientInterceptor extends WorkflowClientInterceptorBase {
  private final OpenTracingOptions options;
  private final SpanFactory spanFactory;
  private final ContextAccessor contextAccessor;

  public OpenTracingClientInterceptor() {
    this(OpenTracingOptions.getDefaultInstance());
  }

  public OpenTracingClientInterceptor(OpenTracingOptions options) {
    this.options = options;
    this.spanFactory = new SpanFactory(options);
    this.contextAccessor = new ContextAccessor(options);
  }

  @Override
  public WorkflowClientCallsInterceptor workflowClientCallsInterceptor(
      WorkflowClientCallsInterceptor next) {
    return new OpenTracingWorkflowClientCallsInterceptor(
        next, options, spanFactory, contextAccessor);
  }
}
