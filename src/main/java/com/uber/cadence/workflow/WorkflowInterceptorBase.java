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

package com.uber.cadence.workflow;

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.activity.ActivityOptions;
import com.uber.cadence.workflow.Functions.Func;
import com.uber.cadence.workflow.Functions.Func1;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

public class WorkflowInterceptorBase implements WorkflowInterceptor {

  private final WorkflowInterceptor next;

  public WorkflowInterceptorBase(WorkflowInterceptor next) {
    this.next = next;
  }

  @Override
  public <R> Promise<R> executeActivity(
      String activityName, Class<R> returnType, Object[] args, ActivityOptions options) {
    return next.executeActivity(activityName, returnType, args, options);
  }

  @Override
  public <R> WorkflowResult<R> executeChildWorkflow(
      String workflowType, Class<R> returnType, Object[] args, ChildWorkflowOptions options) {
    return next.executeChildWorkflow(workflowType, returnType, args, options);
  }

  @Override
  public Promise<Void> signalExternalWorkflow(
      WorkflowExecution execution, String signalName, Object[] args) {
    return next.signalExternalWorkflow(execution, signalName, args);
  }

  @Override
  public Promise<Void> cancelWorkflow(WorkflowExecution execution) {
    return next.cancelWorkflow(execution);
  }

  @Override
  public void sleep(Duration duration) {
    next.sleep(duration);
  }

  @Override
  public boolean await(Duration timeout, String reason, Supplier<Boolean> unblockCondition) {
    return next.await(timeout, reason, unblockCondition);
  }

  @Override
  public void await(String reason, Supplier<Boolean> unblockCondition) {
    next.await(reason, unblockCondition);
  }

  @Override
  public Promise<Void> newTimer(Duration duration) {
    return next.newTimer(duration);
  }

  @Override
  public <R> R sideEffect(Class<R> resultType, Func<R> func) {
    return next.sideEffect(resultType, func);
  }

  @Override
  public void continueAsNew(
      Optional<String> workflowType, Optional<ContinueAsNewOptions> options, Object[] args) {
    next.continueAsNew(workflowType, options, args);
  }

  @Override
  public void registerQuery(
      String queryType, Class<?>[] argTypes, Func1<Object[], Object> callback) {
    next.registerQuery(queryType, argTypes, callback);
  }
}
