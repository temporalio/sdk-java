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
 * The information about the activity task that the current activity is handling. Use {@link
 * ActivityExecutionContext#getInfo()} to access.
 */
public interface ActivityInfo {

  /**
   * A correlation token that can be used to complete the activity asynchronously through {@link
   * io.temporal.client.ActivityCompletionClient#complete(byte[], Object)}.
   */
  byte[] getTaskToken();

  /** WorkflowId of the workflow that scheduled the activity. */
  String getWorkflowId();

  /** RunId of the workflow that scheduled the activity. */
  String getRunId();

  /**
   * ID of the activity. This ID can be used to complete the activity asynchronously through {@link
   * io.temporal.client.ActivityCompletionClient#complete(String, Optional, String, Object)}.
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

  Optional<Payloads> getHeartbeatDetails();

  String getWorkflowType();

  String getWorkflowNamespace();

  String getActivityNamespace();

  /** Activity execution attempt starting from 1. */
  int getAttempt();

  /** Is this activity invoked as a local activity? */
  boolean isLocal();
}
