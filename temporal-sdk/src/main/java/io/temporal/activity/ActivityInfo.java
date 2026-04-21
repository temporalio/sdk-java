package io.temporal.activity;

import io.temporal.api.common.v1.Payloads;
import io.temporal.common.Experimental;
import io.temporal.common.Priority;
import io.temporal.common.RetryOptions;
import java.time.Duration;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Information about the Activity Task that the current Activity Execution is handling. Use {@link
 * ActivityExecutionContext#getInfo()} to access.
 */
public interface ActivityInfo {

  /**
   * @return a correlation token that can be used to complete the Activity Execution asynchronously
   *     through {@link io.temporal.client.ActivityCompletionClient#complete(byte[], Object)}.
   */
  byte[] getTaskToken();

  /**
   * @return WorkflowId of the Workflow Execution that scheduled the Activity Execution, or {@code
   *     null} for standalone activities not scheduled by a workflow.
   */
  @Nullable
  String getWorkflowId();

  /**
   * @return RunId of the Workflow Execution that scheduled the Activity Execution, or {@code null}
   *     for standalone activities not scheduled by a workflow.
   * @deprecated use {@link #getWorkflowRunId()}
   */
  @Deprecated
  @Nullable
  String getRunId();

  /**
   * @return RunId of the Workflow Execution that scheduled the Activity Execution, or {@code null}
   *     for standalone activities not scheduled by a workflow.
   */
  @Nullable
  String getWorkflowRunId();

  /**
   * @return the run ID of this standalone Activity Execution, or {@code null} for activities
   *     scheduled by a workflow.
   */
  @Nullable
  String getActivityRunId();

  /**
   * ID of the Activity Execution. This ID can be used to complete the Activity Execution
   * asynchronously through {@link io.temporal.client.ActivityCompletionClient#complete(String,
   * Optional, String, Object)}.
   */
  String getActivityId();

  /**
   * @return type of the Activity.
   */
  String getActivityType();

  /**
   * Time when the Activity Execution was initially scheduled by the Workflow Execution.
   *
   * @return Timestamp in milliseconds (UNIX Epoch time)
   */
  long getScheduledTimestamp();

  /**
   * Time when the Activity Task (current attempt) was started.
   *
   * @return Timestamp in milliseconds (UNIX Epoch time)
   */
  long getStartedTimestamp();

  /**
   * Time when the Activity Task (current attempt) was scheduled by the Temporal Server.
   *
   * @return Timestamp in milliseconds (UNIX Epoch time)
   */
  long getCurrentAttemptScheduledTimestamp();

  /**
   * @return the Schedule-To-Close Timeout setting as a Duration.
   */
  Duration getScheduleToCloseTimeout();

  /**
   * @return the Start-To-Close Timeout setting as a Duration.
   */
  Duration getStartToCloseTimeout();

  /**
   * @return the Heartbeat Timeout setting as a Duration. {@link Duration#ZERO} if absent
   */
  @Nonnull
  Duration getHeartbeatTimeout();

  Optional<Payloads> getHeartbeatDetails();

  /**
   * @return the Workflow Type of the Workflow Execution that executed the Activity, or {@code null}
   *     for standalone activities not scheduled by a workflow.
   */
  @Nullable
  String getWorkflowType();

  /**
   * Note: At some moment Temporal had built-in support for scheduling activities on a different
   * namespace than the original workflow. Currently, Workflows can schedule activities only on the
   * same namespace, hence no need for different {@code getWorkflowNamespace()} and {@link
   * #getActivityNamespace()} methods.
   *
   * @return the Namespace of Workflow Execution that scheduled the Activity, or the activity
   *     namespace for standalone activities.
   * @deprecated use {@link #getNamespace()}
   */
  @Deprecated
  @Nullable
  String getWorkflowNamespace();

  /**
   * Note: At some moment Temporal had built-in support for scheduling activities on a different
   * namespace than the original workflow. Currently, Workflows can schedule activities only on the
   * same namespace, hence no need for different {@link #getWorkflowNamespace()} and {@code
   * getActivityNamespace()} methods.
   *
   * @return the Namespace of this Activity Execution.
   * @deprecated use {@link #getNamespace()}
   */
  @Deprecated
  @Nullable
  String getActivityNamespace();

  String getNamespace();

  /**
   * @return {@code true} if this activity was scheduled by a workflow execution; {@code false} for
   *     standalone activities started via {@link io.temporal.client.ActivityClient#start}.
   */
  default boolean isInWorkflow() {
    return getWorkflowId() != null;
  }

  String getActivityTaskQueue();

  /**
   * Gets the current Activity Execution attempt count. Attempt counts start at 1 and increment on
   * each Activity Task Execution retry.
   */
  int getAttempt();

  /** Used to determine if the Activity Execution is a local Activity. */
  boolean isLocal();

  /**
   * Return the priority of the activity task.
   *
   * <p>Note: If unset or on an older server version, this method will return {@link
   * Priority#getDefaultInstance()}.
   */
  @Experimental
  @Nonnull
  Priority getPriority();

  /**
   * @return Retry options for the Activity Execution.
   */
  RetryOptions getRetryOptions();
}
