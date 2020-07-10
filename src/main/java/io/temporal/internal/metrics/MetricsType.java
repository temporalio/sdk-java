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
  public static final String TEMPORAL_METRICS_PREFIX = "temporal-";
  public static final String WORKFLOW_COMPLETED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "workflow-completed";
  public static final String WORKFLOW_CANCELLED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "workflow-canceled";
  public static final String WORKFLOW_FAILED_COUNTER = TEMPORAL_METRICS_PREFIX + "workflow-failed";
  public static final String WORKFLOW_CONTINUE_AS_NEW_COUNTER =
      TEMPORAL_METRICS_PREFIX + "workflow-continue-as-new";
  // measure workflow execution from start to close
  public static final String WORKFLOW_E2E_LATENCY =
      TEMPORAL_METRICS_PREFIX + "workflow-endtoend-latency";
  public static final String DECISION_TASK_REPLAY_LATENCY =
      TEMPORAL_METRICS_PREFIX + "decision-task-replay-latency";

  public static final String DECISION_POLL_COUNTER =
      TEMPORAL_METRICS_PREFIX + "decision-poll-total";
  public static final String DECISION_POLL_FAILED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "decision-poll-failed";
  public static final String DECISION_POLL_TRANSIENT_FAILED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "decision-poll-transient-failed";
  public static final String DECISION_POLL_NO_TASK_COUNTER =
      TEMPORAL_METRICS_PREFIX + "decision-poll-no-task";
  public static final String DECISION_POLL_SUCCEED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "decision-poll-succeed";
  public static final String DECISION_POLL_LATENCY =
      TEMPORAL_METRICS_PREFIX + "decision-poll-latency"; // measure succeed poll request latency
  public static final String DECISION_SCHEDULED_TO_START_LATENCY =
      TEMPORAL_METRICS_PREFIX + "decision-scheduled-to-start-latency";
  public static final String DECISION_EXECUTION_FAILED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "decision-execution-failed";
  public static final String DECISION_EXECUTION_LATENCY =
      TEMPORAL_METRICS_PREFIX + "decision-execution-latency";
  public static final String DECISION_TASK_ERROR_COUNTER =
      TEMPORAL_METRICS_PREFIX + "decision-task-error";

  public static final String ACTIVITY_POLL_NO_TASK_COUNTER =
      TEMPORAL_METRICS_PREFIX + "activity-poll-no-task";
  public static final String ACTIVITY_SCHEDULE_TO_START_LATENCY =
      TEMPORAL_METRICS_PREFIX + "activity-scheduled-to-start-latency";
  public static final String ACTIVITY_EXEC_FAILED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "activity-execution-failed";
  public static final String ACTIVITY_EXEC_LATENCY =
      TEMPORAL_METRICS_PREFIX + "activity-execution-latency";
  public static final String ACTIVITY_E2E_LATENCY =
      TEMPORAL_METRICS_PREFIX + "activity-endtoend-latency";
  public static final String ACTIVITY_TASK_ERROR_COUNTER =
      TEMPORAL_METRICS_PREFIX + "activity-task-error";
  public static final String LOCAL_ACTIVITY_TOTAL_COUNTER =
      TEMPORAL_METRICS_PREFIX + "local-activity-total";
  public static final String LOCAL_ACTIVITY_CANCELED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "local-activity-canceled";
  public static final String LOCAL_ACTIVITY_FAILED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "local-activity-failed";
  public static final String LOCAL_ACTIVITY_ERROR_COUNTER =
      TEMPORAL_METRICS_PREFIX + "local-activity-panic";
  public static final String LOCAL_ACTIVITY_EXECUTION_LATENCY =
      TEMPORAL_METRICS_PREFIX + "local-activity-execution-latency";
  public static final String CORRUPTED_SIGNALS_COUNTER =
      TEMPORAL_METRICS_PREFIX + "corrupted-signals";

  public static final String WORKER_START_COUNTER = TEMPORAL_METRICS_PREFIX + "worker-start";
  public static final String POLLER_START_COUNTER = TEMPORAL_METRICS_PREFIX + "poller-start";

  public static final String TEMPORAL_REQUEST = TEMPORAL_METRICS_PREFIX + "request";
  public static final String TEMPORAL_REQUEST_FAILURE = TEMPORAL_REQUEST + "-failure";
  public static final String TEMPORAL_REQUEST_LATENCY = TEMPORAL_REQUEST + "-latency";
  public static final String TEMPORAL_LONG_REQUEST = TEMPORAL_METRICS_PREFIX + "long-request";
  public static final String TEMPORAL_LONG_REQUEST_FAILURE = TEMPORAL_LONG_REQUEST + "-failure";
  public static final String TEMPORAL_LONG_REQUEST_LATENCY = TEMPORAL_LONG_REQUEST + "-latency";

  public static final String STICKY_CACHE_HIT = TEMPORAL_METRICS_PREFIX + "sticky-cache-hit";
  public static final String STICKY_CACHE_MISS = TEMPORAL_METRICS_PREFIX + "sticky-cache-miss";
  public static final String STICKY_CACHE_TOTAL_FORCED_EVICTION =
      TEMPORAL_METRICS_PREFIX + "sticky-cache-total-forced-eviction";
  public static final String STICKY_CACHE_THREAD_FORCED_EVICTION =
      TEMPORAL_METRICS_PREFIX + "sticky-cache-thread-forced-eviction";
  public static final String STICKY_CACHE_SIZE = TEMPORAL_METRICS_PREFIX + "sticky-cache-size";
  public static final String WORKFLOW_ACTIVE_THREAD_COUNT =
      TEMPORAL_METRICS_PREFIX + "workflow-active-thread-count";
}
