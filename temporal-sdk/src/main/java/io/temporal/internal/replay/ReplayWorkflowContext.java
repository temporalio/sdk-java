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

package io.temporal.internal.replay;

import com.uber.m3.tally.Scope;
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.SignalExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.*;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.internal.common.SdkFlag;
import io.temporal.internal.statemachines.ExecuteActivityParameters;
import io.temporal.internal.statemachines.ExecuteLocalActivityParameters;
import io.temporal.internal.statemachines.LocalActivityCallback;
import io.temporal.internal.statemachines.StartChildWorkflowExecutionParameters;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Functions.Func;
import io.temporal.workflow.Functions.Func1;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Represents the context of workflow for workflow code. Should only be used within the scope of
 * workflow code, meaning any code which is not part of activity implementations. Provides access to
 * state machine operations and information that should be accessible to the workflow code.
 * Accumulates some state from the workflow execution like search attributes, continue-as-new.
 *
 * <p>TODO(maxim): Get rid of any Exceptions in the callbacks. They should only return Failure.
 */
public interface ReplayWorkflowContext extends ReplayAware {

  class ScheduleActivityTaskOutput {
    private final String activityId;
    private final Functions.Proc1<Exception> cancellationHandle;

    public ScheduleActivityTaskOutput(
        String activityId, Functions.Proc1<Exception> cancllationHandle) {
      this.activityId = activityId;
      this.cancellationHandle = cancllationHandle;
    }

    public String getActivityId() {
      return activityId;
    }

    public Functions.Proc1<Exception> getCancellationHandle() {
      return cancellationHandle;
    }
  }

  WorkflowExecution getWorkflowExecution();

  WorkflowExecution getParentWorkflowExecution();

  WorkflowType getWorkflowType();

  /**
   * Note: RunId is unique identifier of one workflow code execution. Reset changes RunId.
   *
   * @return Workflow Run ID that is handled by the current workflow code execution.
   * @see #getOriginalExecutionRunId() for RunId variation that is resistant to Resets
   * @see #getFirstExecutionRunId() for the very first RunId that is preserved along the whole
   *     Workflow Execution chain, including ContinueAsNew, Retry, Cron and Reset.
   */
  @Nonnull
  String getRunId();

  /**
   * @return The very first original RunId of the current Workflow Execution preserved along the
   *     chain of ContinueAsNew, Retry, Cron and Reset. Identifies the whole Runs chain of Workflow
   *     Execution.
   */
  @Nonnull
  String getFirstExecutionRunId();

  /**
   * @return Run ID of the previous Workflow Run which continued-as-new or retried or cron-scheduled
   *     into the current Workflow Run.
   */
  Optional<String> getContinuedExecutionRunId();

  /**
   * Note: This value is NOT preserved by continue-as-new, retries or cron Runs. They are separate
   * Runs of one Workflow Execution Chain.
   *
   * @return original RunId of the current Workflow Run. This value is preserved during Reset which
   *     changes RunID.
   * @see #getFirstExecutionRunId() for the very first RunId that is preserved along the whole
   *     Workflow Execution chain, including ContinueAsNew, Retry, Cron and Reset.
   */
  @Nonnull
  String getOriginalExecutionRunId();

  /** Workflow task queue name. */
  String getTaskQueue();

  /** Workflow namespace. */
  String getNamespace();

  String getWorkflowId();

  /**
   * @return Timeout for a Workflow Run specified during Workflow start in {@link
   *     io.temporal.client.WorkflowOptions.Builder#setWorkflowRunTimeout(Duration)}
   */
  Duration getWorkflowRunTimeout();

  /**
   * @return Timeout for the Workflow Execution specified during Workflow start in {@link
   *     io.temporal.client.WorkflowOptions.Builder#setWorkflowExecutionTimeout(Duration)}
   */
  Duration getWorkflowExecutionTimeout();

  long getRunStartedTimestampMillis();

  @Nonnull
  Duration getWorkflowTaskTimeout();

  Payload getMemo(String key);

  /**
   * Requests an activity execution.
   *
   * @param parameters An object which encapsulates all the information required to schedule an
   *     activity for execution
   * @param callback Callback that is called upon activity completion or failure.
   * @return cancellation handle. Invoke {@link io.temporal.workflow.Functions.Proc1#apply(Object)}
   *     to cancel activity task.
   */
  ScheduleActivityTaskOutput scheduleActivityTask(
      ExecuteActivityParameters parameters, Functions.Proc2<Optional<Payloads>, Failure> callback);

  Functions.Proc scheduleLocalActivityTask(
      ExecuteLocalActivityParameters parameters, LocalActivityCallback callback);

  /**
   * Start child workflow.
   *
   * @param parameters encapsulates all the information required to schedule a child workflow for
   *     execution
   * @param startCallback callback that is called upon child start or failure to start
   * @param completionCallback callback that is called upon child workflow completion or failure
   * @return cancellation handle. Invoke {@link io.temporal.workflow.Functions.Proc1#apply(Object)}
   *     to cancel activity task.
   */
  Functions.Proc1<Exception> startChildWorkflow(
      StartChildWorkflowExecutionParameters parameters,
      Functions.Proc2<WorkflowExecution, Exception> startCallback,
      Functions.Proc2<Optional<Payloads>, Exception> completionCallback);

  /**
   * Signal a workflow execution by WorkflowId and optionally RunId.
   *
   * @param attributes signal information
   * @param callback callback notified about the operation result
   * @return cancellation handler that should be calle to cancel the operation.
   */
  Functions.Proc1<Exception> signalExternalWorkflowExecution(
      SignalExternalWorkflowExecutionCommandAttributes.Builder attributes,
      Functions.Proc2<Void, Failure> callback);

  /**
   * Request cancellation of a workflow execution by WorkflowId and optionally RunId.
   *
   * @param execution contains WorkflowId and optional RunId of the workflow to send request to.
   * @param callback callback notified about the operation result
   */
  void requestCancelExternalWorkflowExecution(
      WorkflowExecution execution, Functions.Proc2<Void, RuntimeException> callback);

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
   * @return cancellation handle. Invoke {@link io.temporal.workflow.Functions.Proc1#apply(Object)}
   *     to cancel timer.
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
   * re-executed giving SideEffect another chance to succeed. Use {@link
   * #scheduleLocalActivityTask(ExecuteLocalActivityParameters, LocalActivityCallback)} for
   * executing operations that rely on non-global dependencies and can fail.
   *
   * @param func function that is called once to return a value.
   * @param callback function that accepts the result of the side effect.
   */
  void sideEffect(Func<Optional<Payloads>> func, Functions.Proc1<Optional<Payloads>> callback);

  /**
   * {@code mutableSideEffect} is similar to {@code sideEffect} in allowing calls of
   * non-deterministic functions from workflow code.
   *
   * <p>The difference between {@code mutableSideEffect} and {@code sideEffect} is that every new
   * {@code sideEffect} call in non-replay mode results in a new marker event recorded into the
   * history. However, {@code mutableSideEffect} only records a new marker if a value has changed.
   * During the replay, {@code mutableSideEffect} will not execute the function again, but it will
   * return the exact same value as it was returning during the non-replay run.
   *
   * <p>One good use case of {@code mutableSideEffect} is to access a dynamically changing config
   * without breaking determinism. Even if called very frequently the config value is recorded only
   * when it changes not causing any performance degradation due to a large history size.
   *
   * <p>Caution: do not use {@code mutableSideEffect} function to modify any workflow state. Only
   * use the mutableSideEffect's return value.
   *
   * @param id id of the side effect call. It links multiple calls together. Calls with different
   *     ids are completely independent.
   * @param func function that gets as input a result of a previous {@code mutableSideEffect} call.
   *     The function executes its business logic (like checking config value) and if value didn't
   *     change returns {@link Optional#empty()}. If value has changed and needs to be recorded in
   *     the history then it is returned instead.
   * @param callback function that accepts the result of the mutable side effect which is current or
   *     cached result of the func.
   */
  void mutableSideEffect(
      String id,
      Func1<Optional<Payloads>, Optional<Payloads>> func,
      Functions.Proc1<Optional<Payloads>> callback);

  /**
   * GetVersion is used to safely perform backwards incompatible changes to workflow definitions. It
   * is not allowed to update workflow code while there are workflows running as it is going to
   * break determinism. The solution is to have both old code that is used to replay existing
   * workflows and the new one that is used when it is executed for the first time. GetVersion
   * returns maxSupported version when executed for the first time. This version is recorded into
   * the workflow history as a marker event. Even if maxSupported version is changed the version
   * that was recorded is returned on replay. DefaultVersion constant contains version of code that
   * wasn't versioned before.
   *
   * @param changeId identifier of a particular change
   * @param minSupported min version supported for the change
   * @param maxSupported max version supported for the change
   * @param callback used to return version
   * @return True if the identifier is not present in history
   */
  boolean getVersion(
      String changeId,
      int minSupported,
      int maxSupported,
      Functions.Proc2<Integer, RuntimeException> callback);

  /** Replay safe random. */
  Random newRandom();

  /**
   * @return scope to be used for metrics reporting.
   */
  Scope getMetricsScope();

  /**
   * @return whether we do logging during workflow code replay.
   */
  boolean getEnableLoggingInReplay();

  /**
   * @return replay safe UUID
   */
  UUID randomUUID();

  /**
   * @return workflow retry attempt. Starts on "1".
   */
  int getAttempt();

  /**
   * @return workflow cron schedule
   */
  String getCronSchedule();

  /**
   * @return Completion result of the last cron workflow run
   */
  @Nullable
  Payloads getLastCompletionResult();

  /**
   * @return Failure of the previous run of this workflow
   */
  @Nullable
  Failure getPreviousRunFailure();

  /**
   * This method is mostly used to decrease logging verbosity for replay-only scenarios.
   *
   * @return query name if an execution is a full replay caused by a direct query, null otherwise
   */
  @Nullable
  String getFullReplayDirectQueryName();

  /**
   * @return workflow header
   */
  Map<String, Payload> getHeader();

  /**
   * @return eventId of the last / currently active workflow task of this workflow
   */
  long getCurrentWorkflowTaskStartedEventId();

  /**
   * @return true if cancellation of the workflow is requested.
   */
  boolean isCancelRequested();

  void setCancelRequested();

  /**
   * @return true if the worker's execution or a or replay of the workflow method finished or failed
   */
  boolean isWorkflowMethodCompleted();

  void setWorkflowMethodCompleted();

  /**
   * When these attributes are present upon completion of the workflow code the ContinueAsNew
   * command is emitted instead of the workflow completion.
   */
  ContinueAsNewWorkflowExecutionCommandAttributes getContinueAsNewOnCompletion();

  void continueAsNewOnCompletion(ContinueAsNewWorkflowExecutionCommandAttributes attributes);

  Throwable getWorkflowTaskFailure();

  /**
   * Can be used by any code (both control and executing in workflow threads) to communicate that
   * something is off, correct handling of Workflow Task is no possible and the worker should fail
   * the Workflow Task.
   *
   * <p>Note that this method is created to be from callback and other places where it may be tricky
   * to propagate an exception. If you usecase is in the main synchronous code of WFT processing
   * worker executor control thread - prefer direct exception throwing or return over using this
   * indirect way.
   *
   * @param failure cause of the workflow task failure, this exception will be propagated by
   *     rethrowing in Workflow Executor thread
   */
  void failWorkflowTask(Throwable failure);

  /**
   * @return search attributes collected during the workflow execution up to the current moment as a
   *     non-deserialized protobuf, null if empty
   */
  @Nullable
  SearchAttributes getSearchAttributes();

  /** Updates or inserts search attributes used to index workflows. */
  void upsertSearchAttributes(@Nonnull SearchAttributes searchAttributes);

  /**
   * @return true if this flag may currently be used.
   */
  boolean tryUseSdkFlag(SdkFlag flag);
}
