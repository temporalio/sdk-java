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

package io.temporal.activity;

import com.google.common.base.Objects;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.MethodRetry;
import io.temporal.common.RetryOptions;
import io.temporal.common.context.ContextPropagator;
import io.temporal.failure.CanceledFailure;
import java.time.Duration;
import java.util.List;

/** Options used to configure how an activity is invoked. */
public final class ActivityOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ActivityOptions options) {
    return new Builder(options);
  }

  @Deprecated
  public static ActivityOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final ActivityOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = ActivityOptions.newBuilder().validateAndBuildWithDefaults();
  }

  public static final class Builder {

    private Duration heartbeatTimeout;
    private Duration scheduleToCloseTimeout;
    private Duration scheduleToStartTimeout;
    private Duration startToCloseTimeout;
    private String taskQueue;
    private RetryOptions retryOptions;
    private List<ContextPropagator> contextPropagators;
    private ActivityCancellationType cancellationType;
    private boolean disableEagerExecution;

    private Builder() {}

    private Builder(ActivityOptions options) {
      if (options == null) {
        return;
      }
      this.taskQueue = options.taskQueue;
      this.heartbeatTimeout = options.heartbeatTimeout;
      this.retryOptions = options.retryOptions;
      this.contextPropagators = options.contextPropagators;
      this.scheduleToCloseTimeout = options.scheduleToCloseTimeout;
      this.startToCloseTimeout = options.startToCloseTimeout;
      this.scheduleToStartTimeout = options.scheduleToStartTimeout;
      this.cancellationType = options.cancellationType;
      this.disableEagerExecution = options.disableEagerExecution;
    }

    /**
     * Total time that a workflow is willing to wait for an Activity to complete.
     *
     * <p>ScheduleToCloseTimeout limits the total time of an Activity's execution including retries
     * (use {@link #setStartToCloseTimeout(Duration)} to limit the time of a single attempt).
     *
     * <p>Either this option or {@link #setStartToCloseTimeout(Duration)} is required.
     *
     * <p>Defaults to unlimited, which is chosen if set to null.
     */
    public Builder setScheduleToCloseTimeout(Duration scheduleToCloseTimeout) {
      this.scheduleToCloseTimeout = scheduleToCloseTimeout;
      return this;
    }

    /**
     * Time that the Activity Task can stay in the Task Queue before it is picked up by a Worker.
     *
     * <p>ScheduleToStartTimeout is always non-retryable. Retrying after this timeout doesn't make
     * sense as it would just put the Activity Task back into the same Task Queue.
     *
     * <p>Defaults to unlimited.
     */
    public Builder setScheduleToStartTimeout(Duration scheduleToStartTimeout) {
      this.scheduleToStartTimeout = scheduleToStartTimeout;
      return this;
    }

    /**
     * Maximum time of a single Activity attempt.
     *
     * <p>Note that the Temporal Server doesn't detect Worker process failures directly. It relies
     * on this timeout to detect that an Activity that didn't complete on time. So this timeout
     * should be as short as the longest possible execution of the Activity body. Potentially
     * long-running Activities must specify HeartbeatTimeout and call {@link
     * ActivityExecutionContext#heartbeat(Object)} periodically for timely failure detection.
     *
     * <p>If {@link #setScheduleToCloseTimeout(Duration)} is not provided, then this timeout is
     * required.
     */
    public Builder setStartToCloseTimeout(Duration startToCloseTimeout) {
      this.startToCloseTimeout = startToCloseTimeout;
      return this;
    }

    /**
     * Heartbeat interval. Activity must call {@link ActivityExecutionContext#heartbeat(Object)}
     * before this interval passes after the last heartbeat or the Activity starts.
     */
    public Builder setHeartbeatTimeout(Duration heartbeatTimeoutSeconds) {
      this.heartbeatTimeout = heartbeatTimeoutSeconds;
      return this;
    }

    /**
     * Task queue to use when dispatching activity task to a worker. By default, it is the same task
     * list name the workflow was started with.
     */
    public Builder setTaskQueue(String taskQueue) {
      this.taskQueue = taskQueue;
      return this;
    }

    /**
     * RetryOptions that define how an Activity is retried in case of failure.
     *
     * <p>If not provided, the server-defined default activity retry policy will be used. If not
     * overridden, the server default activity retry policy is:
     *
     * <pre><code>
     *   InitialInterval:         1 second
     *   BackoffCoefficient:      2
     *   MaximumInterval:         100 seconds   // 100 * InitialInterval
     *   MaximumAttempts:         0             // Unlimited
     *   NonRetryableErrorTypes:  []
     * </pre></code>
     *
     * <p>If both {@link #setScheduleToCloseTimeout(Duration)} and {@link
     * RetryOptions.Builder#setMaximumAttempts(int)} are not set, the Activity will not be retried.
     *
     * <p>To ensure zero retries, set {@link RetryOptions.Builder#setMaximumAttempts(int)} to 1.
     */
    public Builder setRetryOptions(RetryOptions retryOptions) {
      this.retryOptions = retryOptions;
      return this;
    }

    /**
     * Note: <br>
     * This method has extremely limited usage. The majority of users should just set {@link
     * WorkflowClientOptions#getContextPropagators()}
     *
     * <p>Both "client" (workflow worker) and "server" (activity worker) sides of context
     * propagation from a workflow to an activity exist in a worker process (potentially the same
     * one), so they typically share the same worker options. Specifically, {@code
     * ContextPropagator}s specified on {@link
     * io.temporal.client.WorkflowClientOptions#getContextPropagators()}. {@link
     * io.temporal.client.WorkflowClientOptions.Builder#setContextPropagators(List)} is the right
     * place to specify {@code ContextPropagator}s between Workflow and an Activity. <br>
     * Specifying context propagators with this method overrides them only on the "client"
     * (workflow) side and can't be automatically promoted to the "server" (activity worker), which
     * always uses {@code ContextPropagator}s from {@link
     * io.temporal.client.WorkflowClientOptions#getContextPropagators()} <br>
     * The only legitimate usecase for this method is probably a situation when the specific
     * activity is implemented in a different language and in a completely different worker codebase
     * and in that case setting a {@code ContextPropagator} that is applied only on a "client" side
     * could make sense. <br>
     * This is also why there is no equivalent method on {@link LocalActivityOptions}.
     *
     * @see <a href="https://github.com/temporalio/sdk-java/issues/490">Rejected feature reqest for
     *     LocalActivityOption#contextPropagators</a>
     * @param contextPropagators specifies the list of context propagators to use during propagation
     *     from a workflow to the activity with these {@link ActivityOptions}. This list overrides
     *     the list specified on {@link
     *     io.temporal.client.WorkflowClientOptions#getContextPropagators()}, {@code null} means no
     *     overriding
     */
    public Builder setContextPropagators(List<ContextPropagator> contextPropagators) {
      this.contextPropagators = contextPropagators;
      return this;
    }

    /**
     * In case of an activity's scope cancellation the corresponding activity stub call fails with a
     * {@link CanceledFailure}.
     *
     * @param cancellationType Defines the activity's stub cancellation mode. The default value is
     *     {@link ActivityCancellationType#TRY_CANCEL}
     * @see ActivityCancellationType
     */
    public Builder setCancellationType(ActivityCancellationType cancellationType) {
      this.cancellationType = cancellationType;
      return this;
    }

    /**
     * If set to true, will not request eager execution regardless of worker settings. If false,
     * eager execution may still be disabled at the worker level or eager execution may not be
     * requested due to lack of available slots.
     *
     * <p>Eager activity execution means the server returns requested eager activities directly from
     * the workflow task back to this worker which is faster than non-eager which may be dispatched
     * to a separate worker.
     *
     * <p>Defaults to false, meaning that eager activity execution will be requested if possible.
     */
    public Builder setDisableEagerExecution(boolean disableEagerExecution) {
      this.disableEagerExecution = disableEagerExecution;
      return this;
    }

    public Builder mergeActivityOptions(ActivityOptions override) {
      if (override == null) {
        return this;
      }
      this.taskQueue = (override.taskQueue == null) ? this.taskQueue : override.taskQueue;
      this.heartbeatTimeout =
          (override.heartbeatTimeout == null) ? this.heartbeatTimeout : override.heartbeatTimeout;
      this.retryOptions =
          (override.retryOptions == null) ? this.retryOptions : override.retryOptions;
      this.scheduleToCloseTimeout =
          (override.scheduleToCloseTimeout == null)
              ? this.scheduleToCloseTimeout
              : override.scheduleToCloseTimeout;
      this.startToCloseTimeout =
          (override.startToCloseTimeout == null)
              ? this.startToCloseTimeout
              : override.startToCloseTimeout;
      this.scheduleToStartTimeout =
          (override.scheduleToStartTimeout == null)
              ? this.scheduleToStartTimeout
              : override.scheduleToStartTimeout;
      this.cancellationType =
          (override.cancellationType == null) ? this.cancellationType : override.cancellationType;
      if (this.contextPropagators == null) {
        this.contextPropagators = override.contextPropagators;
      } else if (override.contextPropagators != null) {
        this.contextPropagators.addAll(override.contextPropagators);
      }
      return this;
    }

    /**
     * Properties that are set on this builder take precedence over ones found in the annotation.
     */
    public Builder mergeMethodRetry(MethodRetry r) {
      retryOptions = RetryOptions.merge(r, retryOptions);
      return this;
    }

    public ActivityOptions build() {
      return new ActivityOptions(
          heartbeatTimeout,
          scheduleToCloseTimeout,
          scheduleToStartTimeout,
          startToCloseTimeout,
          taskQueue,
          retryOptions,
          contextPropagators,
          cancellationType,
          disableEagerExecution);
    }

    public ActivityOptions validateAndBuildWithDefaults() {
      return new ActivityOptions(
          heartbeatTimeout,
          scheduleToCloseTimeout,
          scheduleToStartTimeout,
          startToCloseTimeout,
          taskQueue,
          retryOptions,
          contextPropagators,
          cancellationType == null ? ActivityCancellationType.TRY_CANCEL : cancellationType,
          disableEagerExecution);
    }
  }

  private final Duration heartbeatTimeout;
  private final Duration scheduleToCloseTimeout;
  private final Duration scheduleToStartTimeout;
  private final Duration startToCloseTimeout;
  private final String taskQueue;
  private final RetryOptions retryOptions;
  private final List<ContextPropagator> contextPropagators;
  private final ActivityCancellationType cancellationType;
  private final boolean disableEagerExecution;

  private ActivityOptions(
      Duration heartbeatTimeout,
      Duration scheduleToCloseTimeout,
      Duration scheduleToStartTimeout,
      Duration startToCloseTimeout,
      String taskQueue,
      RetryOptions retryOptions,
      List<ContextPropagator> contextPropagators,
      ActivityCancellationType cancellationType,
      boolean disableEagerExecution) {
    this.heartbeatTimeout = heartbeatTimeout;
    this.scheduleToStartTimeout = scheduleToStartTimeout;
    this.scheduleToCloseTimeout = scheduleToCloseTimeout;
    this.startToCloseTimeout = startToCloseTimeout;
    this.taskQueue = taskQueue;
    this.retryOptions = retryOptions;
    this.contextPropagators = contextPropagators;
    this.cancellationType = cancellationType;
    this.disableEagerExecution = disableEagerExecution;
  }

  /**
   * @see ActivityOptions.Builder#setHeartbeatTimeout(Duration)
   */
  public Duration getHeartbeatTimeout() {
    return heartbeatTimeout;
  }

  /**
   * @see ActivityOptions.Builder#setScheduleToCloseTimeout(Duration)
   */
  public Duration getScheduleToCloseTimeout() {
    return scheduleToCloseTimeout;
  }

  /**
   * @see ActivityOptions.Builder#setScheduleToStartTimeout(Duration)
   */
  public Duration getScheduleToStartTimeout() {
    return scheduleToStartTimeout;
  }

  /**
   * @see ActivityOptions.Builder#setStartToCloseTimeout(Duration)
   */
  public Duration getStartToCloseTimeout() {
    return startToCloseTimeout;
  }

  /**
   * @see ActivityOptions.Builder#setTaskQueue(String)
   */
  public String getTaskQueue() {
    return taskQueue;
  }

  /**
   * @see ActivityOptions.Builder#setRetryOptions(RetryOptions)
   */
  public RetryOptions getRetryOptions() {
    return retryOptions;
  }

  /**
   * @see ActivityOptions.Builder#setContextPropagators(List)
   */
  public List<ContextPropagator> getContextPropagators() {
    return contextPropagators;
  }

  /**
   * @see ActivityOptions.Builder#setCancellationType(ActivityCancellationType)
   */
  public ActivityCancellationType getCancellationType() {
    return cancellationType;
  }

  public boolean isEagerExecutionDisabled() {
    return disableEagerExecution;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ActivityOptions that = (ActivityOptions) o;
    return cancellationType == that.cancellationType
        && Objects.equal(heartbeatTimeout, that.heartbeatTimeout)
        && Objects.equal(scheduleToCloseTimeout, that.scheduleToCloseTimeout)
        && Objects.equal(scheduleToStartTimeout, that.scheduleToStartTimeout)
        && Objects.equal(startToCloseTimeout, that.startToCloseTimeout)
        && Objects.equal(taskQueue, that.taskQueue)
        && Objects.equal(retryOptions, that.retryOptions)
        && Objects.equal(contextPropagators, that.contextPropagators)
        && disableEagerExecution == that.disableEagerExecution;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        heartbeatTimeout,
        scheduleToCloseTimeout,
        scheduleToStartTimeout,
        startToCloseTimeout,
        taskQueue,
        retryOptions,
        contextPropagators,
        cancellationType,
        disableEagerExecution);
  }

  @Override
  public String toString() {
    return "ActivityOptions{"
        + "heartbeatTimeout="
        + heartbeatTimeout
        + ", scheduleToCloseTimeout="
        + scheduleToCloseTimeout
        + ", scheduleToStartTimeout="
        + scheduleToStartTimeout
        + ", startToCloseTimeout="
        + startToCloseTimeout
        + ", taskQueue='"
        + taskQueue
        + '\''
        + ", retryOptions="
        + retryOptions
        + ", contextPropagators="
        + contextPropagators
        + ", abandonOnCancellation="
        + cancellationType
        + ", disableEagerExecution="
        + disableEagerExecution
        + '}';
  }
}
