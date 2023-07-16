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

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.CancelExternalWorkflowException;
import io.temporal.workflow.ExternalWorkflowStub;
import io.temporal.workflow.Promise;
import io.temporal.workflow.SignalExternalWorkflowException;
import java.util.Objects;

/** Dynamic implementation of a strongly typed child workflow interface. */
class ExternalWorkflowStubImpl implements ExternalWorkflowStub {

  private final WorkflowOutboundCallsInterceptor outboundCallsInterceptor;
  private final WorkflowExecution execution;

  public ExternalWorkflowStubImpl(
      WorkflowExecution execution, WorkflowOutboundCallsInterceptor outboundCallsInterceptor) {
    this.outboundCallsInterceptor = Objects.requireNonNull(outboundCallsInterceptor);
    this.execution = Objects.requireNonNull(execution);
  }

  @Override
  public WorkflowExecution getExecution() {
    return execution;
  }

  @Override
  public void signal(String signalName, Object... args) {
    Promise<Void> signaled =
        outboundCallsInterceptor
            .signalExternalWorkflow(
                new WorkflowOutboundCallsInterceptor.SignalExternalInput(
                    execution, signalName, Header.empty(), args))
            .getResult();
    if (AsyncInternal.isAsync()) {
      AsyncInternal.setAsyncResult(signaled);
      return;
    }
    try {
      signaled.get();
    } catch (SignalExternalWorkflowException e) {
      // Reset stack to the current one. Otherwise it is very confusing to see a stack of
      // an event handling method.
      e.setStackTrace(Thread.currentThread().getStackTrace());
      throw e;
    }
  }

  @Override
  public void cancel() {
    Promise<Void> cancelRequested =
        outboundCallsInterceptor
            .cancelWorkflow(new WorkflowOutboundCallsInterceptor.CancelWorkflowInput(execution))
            .getResult();
    if (AsyncInternal.isAsync()) {
      AsyncInternal.setAsyncResult(cancelRequested);
      return;
    }
    try {
      cancelRequested.get();
    } catch (CancelExternalWorkflowException e) {
      // Reset stack to the current one. Otherwise it is very confusing to see a stack of
      // an event handling method.
      e.setStackTrace(Thread.currentThread().getStackTrace());
      throw e;
    }
  }
}
