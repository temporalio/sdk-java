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

import com.uber.cadence.SearchAttributes;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.context.ContextPropagator;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.workflow.Functions.Func;
import com.uber.cadence.workflow.Functions.Func1;
import com.uber.cadence.workflow.Promise;
import com.uber.m3.tally.Scope;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Represents the context for decider. Should only be used within the scope of workflow definition
 * code, meaning any code which is not part of activity implementations.
 */
public interface DecisionContext extends ReplayAware {

  WorkflowExecution getWorkflowExecution();

  WorkflowExecution getParentWorkflowExecution();

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
   * Used to retrieve search attributes.
   *
   * @return SearchAttribute object which can be used by {@code
   *     WorkflowUtils.getValueFromSearchAttributes} to retrieve concrete value.
   */
  SearchAttributes getSearchAttributes();

  /**
   * Returns all of the current contexts being propagated by a {@link
   * com.uber.cadence.context.ContextPropagator}. The key is the {@link ContextPropagator#getName()}
   * and the value is the object returned by {@link ContextPropagator#getCurrentContext()}
   */
  Map<String, Object> getPropagatedContexts();

  /** Returns the set of configured context propagators */
  List<ContextPropagator> getContextPropagators();

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

  Consumer<Exception> scheduleLocalActivityTask(
      ExecuteLocalActivityParameters parameters, BiConsumer<byte[], Exception> callback);

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

  /**
   * Is the next event in the history is child workflow initiated event and it has an attached retry
   * policy. Used for backwards compatibility with the code that used local workflow retry when
   * RetryOptions were specified.
   */
  boolean isServerSideChildWorkflowRetry();

  /**
   * Is the next event in the history is an activity scheduled event and it has an attached retry
   * policy. Used for the backwards compatibility with the code that used local activity retry when
   * RetryOptions were specified.
   */
  boolean isServerSideActivityRetry();

  Consumer<Exception> signalWorkflowExecution(
      SignalExternalWorkflowParameters signalParameters, BiConsumer<Void, Exception> callback);

  Promise<Void> requestCancelWorkflowExecution(WorkflowExecution execution);

  void continueAsNewOnCompletion(ContinueAsNewWorkflowExecutionParameters parameters);

  Optional<byte[]> mutableSideEffect(
      String id, DataConverter dataConverter, Func1<Optional<byte[]>, Optional<byte[]>> func);

  /**
   * @return time of the {@link com.uber.cadence.PollForDecisionTaskResponse} start event of the
   *     decision being processed or replayed.
   */
  long currentTimeMillis();

  /**
   * Create a Value that becomes ready after the specified delay.
   *
   * @param delaySeconds time-interval after which the Value becomes ready in seconds.
   * @param callback Callback that is called with null parameter after the specified delay.
   *     CancellationException is passed as a parameter in case of a cancellation.
   * @return cancellation handle. Invoke {@link Consumer#accept(Object)} to cancel timer.
   */
  Consumer<Exception> createTimer(long delaySeconds, Consumer<Exception> callback);

  /**
   * Executes the provided function once, records its result into the workflow history. The recorded
   * result on history will be returned without executing the provided function during replay. This
   * guarantees the deterministic requirement for workflow as the exact same result will be returned
   * in replay. Common use case is to run some short non-deterministic code in workflow, like
   * getting random number or new UUID. The only way to fail SideEffect is to throw {@link Error}
   * which causes decision task failure. The decision task after timeout is rescheduled and
   * re-executed giving SideEffect another chance to succeed.
   *
   * @param func function that is called once to return a value.
   * @return value of the side effect.
   */
  byte[] sideEffect(Func<byte[]> func);

  /**
   * GetVersion is used to safely perform backwards incompatible changes to workflow definitions. It
   * is not allowed to update workflow code while there are workflows running as it is going to
   * break determinism. The solution is to have both old code that is used to replay existing
   * workflows as well as the new one that is used when it is executed for the first time.
   * GetVersion returns maxSupported version when executed for the first time. This version is
   * recorded into the workflow history as a marker event. Even if maxSupported version is changed
   * the version that was recorded is returned on replay. DefaultVersion constant contains version
   * of code that wasn't versioned before.
   *
   * @param changeID identifier of a particular change
   * @param minSupported min version supported for the change
   * @param maxSupported max version supported for the change
   * @return version
   */
  int getVersion(String changeID, DataConverter dataConverter, int minSupported, int maxSupported);

  Random newRandom();

  /** @return scope to be used for metrics reporting. */
  Scope getMetricsScope();

  /** @return whether we do logging during decision replay. */
  boolean getEnableLoggingInReplay();

  /** @return replay safe UUID */
  UUID randomUUID();

  void upsertSearchAttributes(SearchAttributes searchAttributes);
}
