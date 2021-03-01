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

/**
 * @package io.temporal.activity
 */
package io.temporal.activity;

import io.temporal.api.common.v1.Payloads;
import java.time.Duration;
import java.util.Optional;

/**
 * @entity ActivityInfo
 * @entity.type Interface
 * @entity.headline Information about the Activity Task
 * @entity.description Information about the Activity Task that the current Activity is handling.
 * Use {@link ActivityExecutionContext.getInfo} to access.
 */
public interface ActivityInfo {

  /**
   * @feature getTaskToken
   * @feature.type Method
   * @feature.headline Gets a correlation token that can be used to
   * complete an Ativity asynchronously via {@link ActivityCompletionClient.complete}
   * @feature.return byte[]
   */
  byte[] getTaskToken();

  /**
   * @feature getWorkflowId
   * @feature.type Method
   * @feature.headline WorkflowId of the Workflow that scheduled the Activity
   * @feature.return String
   */
  String getWorkflowId();

  /**
   * @feature getRunId
   * @feature.type Method
   * @feature.headline RunId of the Workflow that scheduled the Activity
   * @feature.return String
   */
  String getRunId();

  /**
   * @feature getActivityId
   * @feature.type Method
   * @feature.headline Id of the Activity
   * @feature.description This Id can be used to complete the Activity Asynchronously
   * via {@link ActivityCompletionClient.complete}
   * @feature.return String
   */
  String getActivityId();

  /**
   * @feature getActivityType
   * @feature.type Method
   * @feature.headline Retrieves the Activity type
   * @feature.return String
   */
  String getActivityType();

  /**
   * @feature getScheduledTimestamp
   * @feature.type Method
   * @feature.headline Time of when the Activity was initially scheduled by the Workflow
   * @feature.return long Timestamp in milliseconds
   */
  long getScheduledTimestamp();

  /**
   * @feature getScheduleToCloseTimeout
   * @feature.type Method
   * @feature.headline Retrieves the schedule-to-close timeout for the Activity
   * @feature.return java.time.Duration
   */
  Duration getScheduleToCloseTimeout();

  /**
   * @feature getStartToCloseTimeout
   * @feature.type Method
   * @feature.headline Retrieves the start-to-close timeout for the Activity
   * @feature.return java.time.Duration
   */
  Duration getStartToCloseTimeout();

  /**
   * @feature getHeartbeatTimeout
   * @feature.type Method
   * @feature.headline Retrieves the heartbeat timeout for the Activity
   * @feature.return java.time.Duration
   */
  Duration getHeartbeatTimeout();

  /**
   * @feature getHeartbeatDetails
   * @feature.type Method
   * @feature.headline Retrieves the heartbeat details for the Activity
   * @feature.return custom payload
   */
  Optional<Payloads> getHeartbeatDetails();

  /**
   * @feature getWorkflowType
   * @feature.type Method
   * @feature.headline Retrieves the Workflow type that invoked the Activity
   * @feature.return String
   */
  String getWorkflowType();

  /**
   * @feature getWorkflowNamespace
   * @feature.type Method
   * @feature.headline Retrieves the Namespace of the Workflow that invoked the Activity
   * @feature.return String
   */
  String getWorkflowNamespace();

  /**
   * @feature getActivityNamespace
   * @feature.type Method
   * @feature.headline Retrieves the Namespace of the Activity
   * @feature.return String
   */
  String getActivityNamespace();

  /**
   * @feature getAttempt
   * @feature.type Method
   * @feature.headline Activity execution attempt starting from 1
   * @feature.return int
   */
  int getAttempt();

  /**
   * @feature isLocal
   * @feature.type Method
   * @feature.headline Is the Activity invoked as a local Activity?
   * @feature.return boolean
   */
  boolean isLocal();
}
