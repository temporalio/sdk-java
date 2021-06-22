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
   * Returns a correlation token that can be used to complete the Activity Execution asynchronously through {@link
   * io.temporal.client.ActivityCompletionClient#complete(byte[], Object)}.
   */
  byte[] getTaskToken();

  /** WorkflowId of the Workflow Execution that scheduled the Activity Execution. */
  String getWorkflowId();

  /** RunId of the Workflow Execution that scheduled the Activity Execution. */
  String getRunId();

  /**
   * ID of the Activity Execution. This ID can be used to complete the Activity Execution asynchronously through {@link
   * io.temporal.client.ActivityCompletionClient#complete(String, Optional, String, Object)}.
   */
  String getActivityId();

  /** Type of the Activity. */
  String getActivityType();

  /**
   * Time when the Activity Execution was initially scheduled by the Workflow Execution.
   *
   * @return Timestamp in milliseconds.
   */
  long getScheduledTimestamp();

  /**
   * Returns the Schedule-To-Close Timeout setting as a Duration.
   */
  Duration getScheduleToCloseTimeout();

  /**
   * Returns the Start-To-Close Timeout setting as a Duration.
   */
  Duration getStartToCloseTimeout();

  /**
   * Returns the Heartbeat Timeout setting as a Duration.
   */
  Duration getHeartbeatTimeout();

  Optional<Payloads> getHeartbeatDetails();

  /**
   * Returns the Workflow Type of the Workflow Execution that executed the Activity.
   */
  String getWorkflowType();

  /**
   * Returns the Namespace of Workflow Execution that executed the Activity.
   */
  String getWorkflowNamespace();

  /**
   * Returns the Namespace of the Activty Execution.
   */
  String getActivityNamespace();

  /**
   * Gets the currents Activity Execution attempt.
   * Attempts start at 1 and increment on each Activity Execution retry.
   */
  int getAttempt();

  /**
   * Use to determine if the Activity Execution is a local Activity.
   */
  boolean isLocal();
}
