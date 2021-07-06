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

package io.temporal.activity;

import io.temporal.api.common.v1.Payloads;
import java.time.Duration;
import java.util.Optional;

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

  /** @return WorkflowId of the Workflow Execution that scheduled the Activity Execution. */
  String getWorkflowId();

  /** @return RunId of the Workflow Execution that scheduled the Activity Execution. */
  String getRunId();

  /**
   * ID of the Activity Execution. This ID can be used to complete the Activity Execution
   * asynchronously through {@link io.temporal.client.ActivityCompletionClient#complete(String,
   * Optional, String, Object)}.
   */
  String getActivityId();

  /** @return type of the Activity. */
  String getActivityType();

  /**
   * Time when the Activity Execution was initially scheduled by the Workflow Execution.
   *
   * @return Timestamp in milliseconds.
   */
  long getScheduledTimestamp();

  /** @return the Schedule-To-Close Timeout setting as a Duration. */
  Duration getScheduleToCloseTimeout();

  /** @return the Start-To-Close Timeout setting as a Duration. */
  Duration getStartToCloseTimeout();

  /** @return the Heartbeat Timeout setting as a Duration. */
  Duration getHeartbeatTimeout();

  Optional<Payloads> getHeartbeatDetails();

  /** @return the Workflow Type of the Workflow Execution that executed the Activity. */
  String getWorkflowType();

  /** @return the Namespace of Workflow Execution that executed the Activity. */
  String getWorkflowNamespace();

  /** @return the Namespace of the Activty Execution. */
  String getActivityNamespace();

  /**
   * Gets the current Activity Execution attempt count. Attempt counts start at 1 and increment on
   * each Activity Task Execution retry.
   */
  int getAttempt();

  /** Used to determine if the Activity Execution is a local Activity. */
  boolean isLocal();
}
