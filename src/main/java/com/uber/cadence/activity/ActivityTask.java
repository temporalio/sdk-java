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

package com.uber.cadence.activity;

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowType;
import java.time.Duration;

/**
 * The information about the activity task that the current activity is handling. Use {@link
 * Activity#getTask()} to access.
 */
public interface ActivityTask {

  /**
   * A correlation token that can be used to complete the activity asynchronously through {@link
   * com.uber.cadence.client.ActivityCompletionClient#complete(byte[], Object)}.
   */
  byte[] getTaskToken();

  /** ID and RunID of the workflow that scheduled the activity. */
  WorkflowExecution getWorkflowExecution();

  /**
   * ID of the activity. This ID can be used to complete the activity asynchronously through {@link
   * com.uber.cadence.client.ActivityCompletionClient#complete(WorkflowExecution, String, Object)}.
   */
  String getActivityId();

  /** Type of the activity. */
  String getActivityType();

  /**
   * Time when the activity was initially scheduled by the workflow.
   *
   * @return timestamp in milliseconds
   */
  long getScheduledTimestamp();

  Duration getScheduleToCloseTimeout();

  Duration getStartToCloseTimeout();

  Duration getHeartbeatTimeout();

  byte[] getHeartbeatDetails();

  WorkflowType getWorkflowType();

  String getWorkflowDomain();

  int getAttempt();
}
