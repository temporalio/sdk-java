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

import com.google.common.base.Defaults;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.workflow.ChildWorkflowException;
import com.uber.cadence.workflow.ChildWorkflowOptions;
import com.uber.cadence.workflow.ChildWorkflowStub;
import com.uber.cadence.workflow.CompletablePromise;
import com.uber.cadence.workflow.Promise;
import com.uber.cadence.workflow.SignalExternalWorkflowException;
import com.uber.cadence.workflow.Workflow;
import java.util.Objects;

class ChildWorkflowStubImpl implements ChildWorkflowStub {

  private final String workflowType;
  private final ChildWorkflowOptions options;
  private final SyncDecisionContext decisionContext;
  private final DataConverter dataConverter;
  private CompletablePromise<WorkflowExecution> execution = Workflow.newPromise();

  ChildWorkflowStubImpl(
      String workflowType, ChildWorkflowOptions options, SyncDecisionContext decisionContext) {
    this.workflowType = Objects.requireNonNull(workflowType);
    this.options = new ChildWorkflowOptions.Builder(options).validateAndBuildWithDefaults();
    this.decisionContext = Objects.requireNonNull(decisionContext);
    dataConverter = Objects.requireNonNull(decisionContext.getDataConverter());
  }

  @Override
  public String getWorkflowType() {
    return workflowType;
  }

  @Override
  public Promise<WorkflowExecution> getExecution() {
    return execution;
  }

  @Override
  public ChildWorkflowOptions getOptions() {
    return options;
  }

  @Override
  public <R> R execute(Class<R> returnType, Object... args) {
    Promise<R> result = executeAsync(returnType, args);
    if (AsyncInternal.isAsync()) {
      AsyncInternal.setAsyncResult(result);
      return Defaults.defaultValue(returnType);
    }
    try {
      return result.get();
    } catch (ChildWorkflowException e) {
      // Reset stack to the current one. Otherwise it is very confusing to see a stack of
      // an event handling method.
      e.setStackTrace(Thread.currentThread().getStackTrace());
      throw e;
    }
  }

  @Override
  public <R> Promise<R> executeAsync(Class<R> returnType, Object... args) {
    byte[] input = dataConverter.toData(args);
    Promise<byte[]> encodedResult =
        decisionContext.executeChildWorkflow(workflowType, options, input, execution);
    return encodedResult.thenApply((encoded) -> dataConverter.fromData(encoded, returnType));
  }

  @Override
  public void signal(String signalName, Object... args) {
    Promise<Void> signalled = decisionContext.signalWorkflow(execution.get(), signalName, args);
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
}
