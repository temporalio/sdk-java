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

package io.temporal.internal.sync;

import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import javax.annotation.Nonnull;

/**
 * Provides core functionality for a root WorkflowInboundCallsInterceptor that is reused by specific
 * root RootWorkflowInboundCallsInterceptor implementations inside {@link
 * DynamicSyncWorkflowDefinition} and {@link POJOWorkflowImplementationFactory}
 *
 * <p>Root {@code WorkflowInboundCallsInterceptor} is an interceptor that should be at the end of
 * the {@link WorkflowInboundCallsInterceptor} interceptors chain and which encapsulates calls into
 * Temporal internals while providing a WorkflowInboundCallsInterceptor interface for chaining on
 * top of it.
 */
public abstract class BaseRootWorkflowInboundCallsInterceptor
    implements WorkflowInboundCallsInterceptor {
  protected final SyncWorkflowContext workflowContext;

  public BaseRootWorkflowInboundCallsInterceptor(SyncWorkflowContext workflowContext) {
    this.workflowContext = workflowContext;
  }

  @Override
  public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
    workflowContext.initHeadOutboundCallsInterceptor(outboundCalls);
  }

  @Override
  public void handleSignal(SignalInput input) {
    workflowContext.handleInterceptedSignal(input);
  }

  @Override
  public QueryOutput handleQuery(QueryInput input) {
    return workflowContext.handleInterceptedQuery(input);
  }

  @Override
  public void validateUpdate(UpdateInput input) {
    workflowContext.handleInterceptedValidateUpdate(input);
  }

  @Override
  public UpdateOutput executeUpdate(UpdateInput input) {
    return workflowContext.handleInterceptedExecuteUpdate(input);
  }

  @Nonnull
  @Override
  public Object newWorkflowMethodThread(Runnable runnable, String name) {
    return workflowContext.newWorkflowMethodThreadIntercepted(runnable, name);
  }

  @Nonnull
  @Override
  public Object newCallbackThread(Runnable runnable, String name) {
    return workflowContext.newWorkflowCallbackThreadIntercepted(runnable, name);
  }
}
