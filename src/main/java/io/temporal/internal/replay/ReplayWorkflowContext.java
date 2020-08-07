/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
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

package io.temporal.internal.replay;

import com.uber.m3.tally.Scope;
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.SignalExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.common.context.ContextPropagator;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Functions.Func;
import io.temporal.workflow.Functions.Func1;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * Represents the context for workflow. Should only be used within the scope of workflow definition
 * code, meaning any code which is not part of activity implementations.
 *
 * <p>TODO(maxim): Get rid of any Exceptions in the callbacks. They should only return Failure.
 */
public interface ReplayWorkflowContext extends ReplayAware {

  WorkflowExecution getWorkflowExecution();

  WorkflowExecution getParentWorkflowExecution();

  WorkflowType getWorkflowType();

  boolean isCancelRequested();

  ContinueAsNewWorkflowExecutionCommandAttributes getContinueAsNewOnCompletion();

  void setContinueAsNewOnCompletion(ContinueAsNewWorkflowExecutionCommandAttributes attributes);

  Optional<String> getContinuedExecutionRunId();

  String getTaskQueue();

  String getNamespace();

  String getWorkflowId();

  String getRunId();

  Duration getWorkflowRunTimeout();

  Duration getWorkflowExecutionTimeout();

  long getRunStartedTimestampMillis();

  long getWorkflowExecutionExpirationTimestampMillis();

  Duration getWorkflowTaskTimeout();

  /**
   * Used to retrieve search attributes.
   *
   * @return SearchAttribute object which can be used by {@code
   *     WorkflowUtils.getValueFromSearchAttributes} to retrieve concrete value.
   */
  SearchAttributes getSearchAttributes();

  /**
   * Returns all of the current contexts being propagated by a {@link
   * io.temporal.common.context.ContextPropagator}. The key is the {@link
   * ContextPropagator#getName()} and the value is the object returned by {@link
   * ContextPropagator#getCurrentContext()}
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
   * @return cancellation handle. Invoke {@link Functions.Proc1#apply(Object)} to cancel activity
   *     task.
   */
  Functions.Proc1<Exception> scheduleActivityTask(
      ExecuteActivityParameters parameters, Functions.Proc2<Optional<Payloads>, Failure> callback);

  Functions.Proc scheduleLocalActivityTask(
      ExecuteLocalActivityParameters parameters,
      Functions.Proc2<Optional<Payloads>, Failure> callback);

  /**
   * Start child workflow.
   *
   * @param parameters An object which encapsulates all the information required to schedule a child
   *     workflow for execution
   * @param callback Callback that is called upon child workflow completion or failure.
   * @return cancellation handle. Invoke {@link Functions.Proc1#apply(Object)} to cancel activity
   *     task.
   */
  Functions.Proc1<Exception> startChildWorkflow(
      StartChildWorkflowExecutionParameters parameters,
      Functions.Proc1<WorkflowExecution> executionCallback,
      Functions.Proc2<Optional<Payloads>, Exception> callback);

  Functions.Proc1<Exception> signalExternalWorkflowExecution(
      SignalExternalWorkflowExecutionCommandAttributes.Builder attributes,
      Functions.Proc2<Void, Failure> callback);

  void requestCancelExternalWorkflowExecution(
      WorkflowExecution execution, Functions.Proc2<Void, RuntimeException> callback);

  void continueAsNewOnCompletion(ContinueAsNewWorkflowExecutionCommandAttributes attributes);

  /**
   * @return time of the {@link PollWorkflowTaskQueueResponse} start event of the workflow task
   *     being processed or replayed.
   */
  long currentTimeMillis();

  /**
   * Create a Value that becomes ready after the specified delay.
   *
   * @param delay time-interval after which the Value becomes ready.
   * @param callback Callback that is called with null parameter after the specified delay.
   *     CanceledException is passed as a parameter in case of a cancellation.
   * @return cancellation handle. Invoke {@link Functions.Proc1#apply(Object)} to cancel timer.
   */
  Functions.Proc1<RuntimeException> newTimer(
      Duration delay, Functions.Proc1<RuntimeException> callback);

  /**
   * Executes the provided function once, records its result into the workflow history. The recorded
   * result on history will be returned without executing the provided function during replay. This
   * guarantees the deterministic requirement for workflow as the exact same result will be returned
   * in replay. Common use case is to run some short non-deterministic code in workflow, like
   * getting random number or new UUID. The only way to fail SideEffect is to throw {@link Error}
   * which causes workflow task failure. The workflow task after timeout is rescheduled and
   * re-executed giving SideEffect another chance to succeed.
   *
   * @param func function that is called once to return a value.
   * @param callback function that accepts the result of the side effect.
   */
  void sideEffect(Func<Optional<Payloads>> func, Functions.Proc1<Optional<Payloads>> callback);

  void mutableSideEffect(
      String id,
      Func1<Optional<Payloads>, Optional<Payloads>> func,
      Functions.Proc1<Optional<Payloads>> callback);

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
   * @param changeId identifier of a particular change
   * @param minSupported min version supported for the change
   * @param maxSupported max version supported for the change
   * @param callback used to return version
   */
  void getVersion(
      String changeId, int minSupported, int maxSupported, Functions.Proc1<Integer> callback);

  Random newRandom();

  /** @return scope to be used for metrics reporting. */
  Scope getMetricsScope();

  /** @return whether we do logging during workflow code replay. */
  boolean getEnableLoggingInReplay();

  /** @return replay safe UUID */
  UUID randomUUID();

  void upsertSearchAttributes(SearchAttributes searchAttributes);
}
