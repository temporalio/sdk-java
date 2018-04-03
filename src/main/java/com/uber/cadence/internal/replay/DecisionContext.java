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

package com.uber.cadence.internal.replay;

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowType;
import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Represents the context for decider. Should only be used within the scope of workflow definition
 * code, meaning any code which is not part of activity implementations.
 */
public interface DecisionContext {

  WorkflowExecution getWorkflowExecution();

  // TODO: Add to Cadence
  //    com.uber.cadence.WorkflowExecution getParentWorkflowExecution();

  WorkflowType getWorkflowType();

  boolean isCancelRequested();

  ContinueAsNewWorkflowExecutionParameters getContinueAsNewOnCompletion();

  void setContinueAsNewOnCompletion(ContinueAsNewWorkflowExecutionParameters continueParameters);

  //    com.uber.cadence.ChildPolicy getChildPolicy();

  //    String getContinuedExecutionRunId();

  int getExecutionStartToCloseTimeoutSeconds();

  String getTaskList();

  String getDomain();

  String getWorkflowId();

  String getRunId();

  Duration getExecutionStartToCloseTimeout();

  Duration getDecisionTaskTimeout();

  /**
   * Used to dynamically schedule an activity for execution
   *
   * @param parameters An object which encapsulates all the information required to schedule an
   *     activity for execution
   * @param callback Callback that is called upon activity completion or failure.
   * @return cancellation handle. Invoke {@link Consumer#accept(Object)} to cancel activity task.
   */
  Consumer<Exception> scheduleActivityTask(
      ExecuteActivityParameters parameters, BiConsumer<byte[], Exception> callback);

  /**
   * Start child workflow.
   *
   * @param parameters An object which encapsulates all the information required to schedule a child
   *     workflow for execution
   * @param callback Callback that is called upon child workflow completion or failure.
   * @return cancellation handle. Invoke {@link Consumer#accept(Object)} to cancel activity task.
   */
  Consumer<Exception> startChildWorkflow(
      StartChildWorkflowExecutionParameters parameters,
      Consumer<WorkflowExecution> executionCallback,
      BiConsumer<byte[], Exception> callback);

  Consumer<Exception> signalWorkflowExecution(
      SignalExternalWorkflowParameters signalParameters, BiConsumer<Void, Exception> callback);

  void requestCancelWorkflowExecution(WorkflowExecution execution);

  void continueAsNewOnCompletion(ContinueAsNewWorkflowExecutionParameters parameters);

  /** Deterministic unique Id generator */
  String generateUniqueId();

  /**
   * @return time of the {@link com.uber.cadence.PollForDecisionTaskResponse} start event of the
   *     decision being processed or replayed.
   */
  long currentTimeMillis();

  /**
   * <code>true</code> indicates if workflow is replaying already processed events to reconstruct it
   * state. <code>false</code> indicates that code is making forward process for the first time. For
   * example can be used to avoid duplicating log records due to replay.
   */
  boolean isReplaying();

  /**
   * Create a Value that becomes ready after the specified delay.
   *
   * @param delaySeconds time-interval after which the Value becomes ready in seconds.
   * @param callback Callback that is called with null parameter after the specified delay.
   *     CancellationException is passed as a parameter in case of a cancellation.
   * @return cancellation handle. Invoke {@link Consumer#accept(Object)} to cancel timer.
   */
  Consumer<Exception> createTimer(long delaySeconds, Consumer<Exception> callback);
}
