package io.temporal.activity;

import io.temporal.api.common.v1.Payloads;
import io.temporal.common.Experimental;
import io.temporal.common.Priority;
import java.time.Duration;
import java.util.Optional;
import javax.annotation.Nonnull;

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
   * @return WorkflowId of the Workflow Execution that scheduled the Activity Execution.
   */
  String getWorkflowId();

  /**
   * @return RunId of the Workflow Execution that scheduled the Activity Execution.
   */
  String getRunId();

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
   * @return the Workflow Type of the Workflow Execution that executed the Activity.
   */
  String getWorkflowType();

  /**
   * Note: At some moment Temporal had built-in support for scheduling activities on a different
   * namespace than the original workflow. Currently, Workflows can schedule activities only on the
   * same namespace, hence no need for different {@code getWorkflowNamespace()} and {@link
   * #getActivityNamespace()} methods.
   *
   * @return the Namespace of Workflow Execution that scheduled the Activity.
   * @deprecated use {@link #getNamespace()}
   */
  @Deprecated
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
  String getActivityNamespace();

  String getNamespace();

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
   * @apiNote If unset or on an older server version, this method will return {@link
   *     Priority#getDefaultInstance()}.
   */
  @Experimental
  @Nonnull
  Priority getPriority();
}
