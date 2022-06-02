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

package io.temporal.common.interceptors;

import javax.annotation.Nonnull;

/** Convenience base class for WorkflowInboundCallsInterceptor implementations. */
public class WorkflowInboundCallsInterceptorBase implements WorkflowInboundCallsInterceptor {
  private final WorkflowInboundCallsInterceptor next;

  public WorkflowInboundCallsInterceptorBase(WorkflowInboundCallsInterceptor next) {
    this.next = next;
  }

  @Override
  public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
    next.init(outboundCalls);
  }

  @Override
  public WorkflowOutput execute(WorkflowInput input) {
    return next.execute(input);
  }

  @Override
  public void handleSignal(SignalInput input) {
    next.handleSignal(input);
  }

  @Override
  public QueryOutput handleQuery(QueryInput input) {
    return next.handleQuery(input);
  }

  @Nonnull
  @Override
  public Object newWorkflowMethodThread(Runnable runnable, String name) {
    return next.newWorkflowMethodThread(runnable, name);
  }

  @Nonnull
  @Override
  public Object newCallbackThread(Runnable runnable, String name) {
    return next.newCallbackThread(runnable, name);
  }
}
