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

package io.temporal.internal.worker;

public class WorkerThreadsNameHelper {
  private static final String LOCAL_ACTIVITY_POLL_THREAD_NAME_PREFIX =
      "Local Activity Poller taskQueue=";
  private static final String ACTIVITY_POLL_THREAD_NAME_PREFIX = "Activity Poller taskQueue=";
  public static final String SHUTDOWN_MANAGER_THREAD_NAME_PREFIX = "TemporalShutdownManager";
  public static final String ACTIVITY_HEARTBEAT_THREAD_NAME_PREFIX = "TemporalActivityHeartbeat-";
  public static final String LOCAL_ACTIVITY_HEARTBEAT_THREAD_NAME_PREFIX =
      "TemporalLocalActivityHeartbeat-";

  public static String getLocalActivityPollerThreadPrefix(String namespace, String taskQueue) {
    return LOCAL_ACTIVITY_POLL_THREAD_NAME_PREFIX
        + "\""
        + taskQueue
        + "\", namespace=\""
        + namespace
        + "\"";
  }

  public static String getActivityPollerThreadPrefix(String namespace, String taskQueue) {
    return ACTIVITY_POLL_THREAD_NAME_PREFIX
        + "\""
        + taskQueue
        + "\", namespace=\""
        + namespace
        + "\"";
  }

  public static String getActivityHeartbeatThreadPrefix(String namespace, String taskQueue) {
    return ACTIVITY_HEARTBEAT_THREAD_NAME_PREFIX + namespace + "-" + taskQueue;
  }

  public static String getLocalActivityHeartbeatThreadPrefix(String namespace, String taskQueue) {
    return LOCAL_ACTIVITY_HEARTBEAT_THREAD_NAME_PREFIX + namespace + "-" + taskQueue;
  }
}
