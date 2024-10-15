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

package io.temporal.internal.worker;

class WorkerThreadsNameHelper {
  private static final String WORKFLOW_POLL_THREAD_NAME_PREFIX = "Workflow Poller taskQueue=";
  private static final String LOCAL_ACTIVITY_POLL_THREAD_NAME_PREFIX =
      "Local Activity Poller taskQueue=";
  private static final String ACTIVITY_POLL_THREAD_NAME_PREFIX = "Activity Poller taskQueue=";
  private static final String NEXUS_POLL_THREAD_NAME_PREFIX = "Nexus Poller taskQueue=";
  public static final String SHUTDOWN_MANAGER_THREAD_NAME_PREFIX = "TemporalShutdownManager";
  public static final String ACTIVITY_HEARTBEAT_THREAD_NAME_PREFIX = "TemporalActivityHeartbeat-";

  public static final String LOCAL_ACTIVITY_SCHEDULER_THREAD_NAME_PREFIX =
      "LocalActivityScheduler-";

  public static String getWorkflowPollerThreadPrefix(String namespace, String taskQueue) {
    return WORKFLOW_POLL_THREAD_NAME_PREFIX
        + "\""
        + taskQueue
        + "\", namespace=\""
        + namespace
        + "\"";
  }

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

  public static String getLocalActivitySchedulerThreadPrefix(String namespace, String taskQueue) {
    return LOCAL_ACTIVITY_SCHEDULER_THREAD_NAME_PREFIX + namespace + "-" + taskQueue;
  }

  public static String getNexusPollerThreadPrefix(String namespace, String taskQueue) {
    return NEXUS_POLL_THREAD_NAME_PREFIX + "\"" + taskQueue + "\", namespace=\"" + namespace + "\"";
  }
}
