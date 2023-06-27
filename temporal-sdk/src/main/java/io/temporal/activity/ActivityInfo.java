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

import io.temporal.api.common.v1.Payloads;
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
}
