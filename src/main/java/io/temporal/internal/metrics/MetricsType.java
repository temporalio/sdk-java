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

package io.temporal.internal.metrics;

public class MetricsType {
  public static final String TEMPORAL_METRICS_PREFIX = "temporal_";
  public static final String WORKFLOW_COMPLETED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "workflow_completed";
  public static final String WORKFLOW_CANCELLED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "workflow_canceled";
  public static final String WORKFLOW_FAILED_COUNTER = TEMPORAL_METRICS_PREFIX + "workflow_failed";
  public static final String WORKFLOW_CONTINUE_AS_NEW_COUNTER =
      TEMPORAL_METRICS_PREFIX + "workflow_continue_as_new";
  // measure workflow execution from start to close
  public static final String WORKFLOW_E2E_LATENCY =
      TEMPORAL_METRICS_PREFIX + "workflow_endtoend_latency";
  public static final String WORKFLOW_TASK_REPLAY_LATENCY =
      TEMPORAL_METRICS_PREFIX + "workflow_task_replay_latency";

  public static final String WORKFLOW_TASK_QUEUE_POLL_EMPTY_COUNTER =
      TEMPORAL_METRICS_PREFIX + "workflow_task_queue_poll_empty";
  public static final String WORKFLOW_TASK_QUEUE_POLL_SUCCEED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "workflow_task_queue_poll_succeed";
  public static final String WORKFLOW_TASK_SCHEDULE_TO_START_LATENCY =
      TEMPORAL_METRICS_PREFIX + "workflow_task_schedule_to_start_latency";
  public static final String WORKFLOW_TASK_EXECUTION_LATENCY =
      TEMPORAL_METRICS_PREFIX + "workflow_task_execution_latency";
  // Total latency of a workflow task which can include multiple synchronous decision tasks
  public static final String WORKFLOW_TASK_EXECUTION_TOTAL_LATENCY =
      TEMPORAL_METRICS_PREFIX + "workflow_task_execution_total_latency";
  /** Workflow task failed, possibly failing workflow or reporting failure to the service. */
  public static final String WORKFLOW_TASK_EXECUTION_FAILURE_COUNTER =
      TEMPORAL_METRICS_PREFIX + "workflow_task_execution_failed";
  /** Workflow task failed with unhandled exception without replying to the service. */
  public static final String WORKFLOW_TASK_NO_COMPLETION_COUNTER =
      TEMPORAL_METRICS_PREFIX + "workflow_task_no_completion";

  public static final String WORKFLOW_TASK_HEARTBEAT_COUNTER =
      TEMPORAL_METRICS_PREFIX + "workflow_task_heartbeat";

  public static final String ACTIVITY_POLL_NO_TASK_COUNTER =
      TEMPORAL_METRICS_PREFIX + "activity_poll_no_task";
  public static final String ACTIVITY_SCHEDULE_TO_START_LATENCY =
      TEMPORAL_METRICS_PREFIX + "activity_scheduled_to_start_latency";
  public static final String ACTIVITY_EXEC_FAILED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "activity_execution_failed";
  public static final String ACTIVITY_EXEC_LATENCY =
      TEMPORAL_METRICS_PREFIX + "activity_execution_latency";
  public static final String ACTIVITY_E2E_LATENCY =
      TEMPORAL_METRICS_PREFIX + "activity_endtoend_latency";
  public static final String ACTIVITY_TASK_ERROR_COUNTER =
      TEMPORAL_METRICS_PREFIX + "activity_task_error";
  public static final String LOCAL_ACTIVITY_TOTAL_COUNTER =
      TEMPORAL_METRICS_PREFIX + "local_activity_total";
  public static final String LOCAL_ACTIVITY_CANCELED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "local_activity_canceled";
  public static final String LOCAL_ACTIVITY_FAILED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "local_activity_failed";
  public static final String LOCAL_ACTIVITY_ERROR_COUNTER =
      TEMPORAL_METRICS_PREFIX + "local_activity_error";
  public static final String LOCAL_ACTIVITY_EXECUTION_LATENCY =
      TEMPORAL_METRICS_PREFIX + "local_activity_execution_latency";
  public static final String CORRUPTED_SIGNALS_COUNTER =
      TEMPORAL_METRICS_PREFIX + "corrupted_signals";

  public static final String WORKER_START_COUNTER = TEMPORAL_METRICS_PREFIX + "worker_start";
  public static final String POLLER_START_COUNTER = TEMPORAL_METRICS_PREFIX + "poller_start";

  public static final String TEMPORAL_REQUEST = TEMPORAL_METRICS_PREFIX + "request";
  public static final String TEMPORAL_REQUEST_FAILURE = TEMPORAL_REQUEST + "_failure";
  public static final String TEMPORAL_REQUEST_LATENCY = TEMPORAL_REQUEST + "_latency";
  public static final String TEMPORAL_LONG_REQUEST = TEMPORAL_METRICS_PREFIX + "long_request";
  public static final String TEMPORAL_LONG_REQUEST_FAILURE = TEMPORAL_LONG_REQUEST + "_failure";
  public static final String TEMPORAL_LONG_REQUEST_LATENCY = TEMPORAL_LONG_REQUEST + "_latency";

  public static final String STICKY_CACHE_HIT = TEMPORAL_METRICS_PREFIX + "sticky_cache_hit";
  public static final String STICKY_CACHE_MISS = TEMPORAL_METRICS_PREFIX + "sticky_cache_miss";
  public static final String STICKY_CACHE_TOTAL_FORCED_EVICTION =
      TEMPORAL_METRICS_PREFIX + "sticky_cache_total_forced_eviction";
  public static final String STICKY_CACHE_THREAD_FORCED_EVICTION =
      TEMPORAL_METRICS_PREFIX + "sticky_cache_thread_forced_eviction";
  public static final String STICKY_CACHE_SIZE = TEMPORAL_METRICS_PREFIX + "sticky_cache_size";
  public static final String WORKFLOW_ACTIVE_THREAD_COUNT =
      TEMPORAL_METRICS_PREFIX + "workflow_active_thread_count";
}
