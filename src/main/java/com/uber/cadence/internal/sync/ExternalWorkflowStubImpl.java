/*
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

package com.uber.cadence.internal.sync;

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.workflow.ExternalWorkflowStub;
import com.uber.cadence.workflow.Promise;
import com.uber.cadence.workflow.SignalExternalWorkflowException;
import java.util.Objects;

/** Dynamic implementation of a strongly typed child workflow interface. */
class ExternalWorkflowStubImpl implements ExternalWorkflowStub {

  private final SyncDecisionContext decisionContext;
  private final DataConverter dataConverter;
  private final WorkflowExecution execution;

  public ExternalWorkflowStubImpl(
      WorkflowExecution execution, SyncDecisionContext decisionContext) {
    this.decisionContext = Objects.requireNonNull(decisionContext);
    dataConverter = Objects.requireNonNull(decisionContext).getDataConverter();
    this.execution = Objects.requireNonNull(execution);
  }

  @Override
  public WorkflowExecution getExecution() {
    return execution;
  }

  @Override
  public void signal(String signalName, Object... args) {
    Promise<Void> signalled = decisionContext.signalWorkflow(execution, signalName, args);
    if (AsyncInternal.isAsync()) {
      AsyncInternal.setAsyncResult(signalled);
      return;
    }
    try {
      signalled.get();
    } catch (SignalExternalWorkflowException e) {
      // Reset stack to the current one. Otherwise it is very confusing to see a stack of
      // an event handling method.
      e.setStackTrace(Thread.currentThread().getStackTrace());
      throw e;
    }
  }

  @Override
  public void cancel() {
    decisionContext.requestCancelWorkflowExecution(execution);
  }
}
