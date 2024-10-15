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

package io.temporal.worker;

import io.temporal.common.Experimental;

public final class MetricsType {
  private MetricsType() {}

  public static final String TEMPORAL_METRICS_PREFIX = "temporal_";

  //
  // Workflow
  //
  public static final String WORKFLOW_COMPLETED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "workflow_completed";
  public static final String WORKFLOW_CANCELED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "workflow_canceled";
  public static final String WORKFLOW_FAILED_COUNTER = TEMPORAL_METRICS_PREFIX + "workflow_failed";
  public static final String WORKFLOW_CONTINUE_AS_NEW_COUNTER =
      TEMPORAL_METRICS_PREFIX + "workflow_continue_as_new";
  public static final String CORRUPTED_SIGNALS_COUNTER =
      TEMPORAL_METRICS_PREFIX + "corrupted_signals";

  /** measure workflow execution from start to close */
  public static final String WORKFLOW_E2E_LATENCY =
      TEMPORAL_METRICS_PREFIX + "workflow_endtoend_latency";

  //
  // WFT
  //
  public static final String WORKFLOW_TASK_QUEUE_POLL_EMPTY_COUNTER =
      TEMPORAL_METRICS_PREFIX + "workflow_task_queue_poll_empty";
  public static final String WORKFLOW_TASK_QUEUE_POLL_SUCCEED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "workflow_task_queue_poll_succeed";

  public static final String WORKFLOW_TASK_SCHEDULE_TO_START_LATENCY =
      TEMPORAL_METRICS_PREFIX + "workflow_task_schedule_to_start_latency";
  public static final String WORKFLOW_TASK_EXECUTION_LATENCY =
      TEMPORAL_METRICS_PREFIX + "workflow_task_execution_latency";
  /** Total latency of a workflow task which can include multiple forced decision tasks */
  public static final String WORKFLOW_TASK_EXECUTION_TOTAL_LATENCY =
      TEMPORAL_METRICS_PREFIX + "workflow_task_execution_total_latency";

  public static final String WORKFLOW_TASK_REPLAY_LATENCY =
      TEMPORAL_METRICS_PREFIX + "workflow_task_replay_latency";

  /** Workflow task failed, possibly failing workflow or reporting failure to the service. */
  public static final String WORKFLOW_TASK_EXECUTION_FAILURE_COUNTER =
      TEMPORAL_METRICS_PREFIX + "workflow_task_execution_failed";
  /**
   * Workflow task failed with unhandled exception without replying to the service.<br>
   * This typically happens when workflow task fails second time in a row.<br>
   * SDK drops the task and emulates a time-out instead of keeping reporting the failure.<br>
   * It's implemented this way to get a sdk controlled backoff behavior.<br>
   */
  public static final String WORKFLOW_TASK_NO_COMPLETION_COUNTER =
      TEMPORAL_METRICS_PREFIX + "workflow_task_no_completion";

  public static final String WORKFLOW_TASK_HEARTBEAT_COUNTER =
      TEMPORAL_METRICS_PREFIX + "workflow_task_heartbeat";

  //
  // Activity
  //
  public static final String ACTIVITY_POLL_NO_TASK_COUNTER =
      TEMPORAL_METRICS_PREFIX + "activity_poll_no_task";

  public static final String ACTIVITY_SCHEDULE_TO_START_LATENCY =
      TEMPORAL_METRICS_PREFIX + "activity_schedule_to_start_latency";
  public static final String ACTIVITY_EXEC_LATENCY =
      TEMPORAL_METRICS_PREFIX + "activity_execution_latency";
  public static final String ACTIVITY_SUCCEED_E2E_LATENCY =
      TEMPORAL_METRICS_PREFIX + "activity_succeed_endtoend_latency";

  public static final String ACTIVITY_EXEC_FAILED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "activity_execution_failed";
  public static final String ACTIVITY_EXEC_CANCELLED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "activity_execution_cancelled";
  /**
   * @deprecated use {@link #ACTIVITY_EXEC_CANCELLED_COUNTER}
   */
  @Deprecated
  public static final String ACTIVITY_CANCELED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "activity_canceled";

  //
  // Local Activity
  //
  public static final String LOCAL_ACTIVITY_TOTAL_COUNTER =
      TEMPORAL_METRICS_PREFIX + "local_activity_total";

  public static final String LOCAL_ACTIVITY_EXECUTION_LATENCY =
      TEMPORAL_METRICS_PREFIX + "local_activity_execution_latency";
  public static final String LOCAL_ACTIVITY_SUCCEED_E2E_LATENCY =
      TEMPORAL_METRICS_PREFIX + "local_activity_succeed_endtoend_latency";

  public static final String LOCAL_ACTIVITY_EXEC_CANCELLED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "local_activity_execution_cancelled";
  /**
   * @deprecated use {@link #LOCAL_ACTIVITY_EXEC_CANCELLED_COUNTER}
   */
  @Deprecated
  public static final String LOCAL_ACTIVITY_CANCELED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "local_activity_canceled";

  public static final String LOCAL_ACTIVITY_EXEC_FAILED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "local_activity_execution_failed";
  /**
   * @deprecated use {@link #LOCAL_ACTIVITY_EXEC_FAILED_COUNTER}
   */
  @Deprecated
  public static final String LOCAL_ACTIVITY_FAILED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "local_activity_failed";

  // Worker internals, tagged with namespace, task_queue, worker_type
  public static final String WORKER_START_COUNTER = TEMPORAL_METRICS_PREFIX + "worker_start";
  public static final String POLLER_START_COUNTER = TEMPORAL_METRICS_PREFIX + "poller_start";
  // gauge
  public static final String WORKER_TASK_SLOTS_AVAILABLE =
      TEMPORAL_METRICS_PREFIX + "worker_task_slots_available";

  @Experimental
  public static final String WORKER_TASK_SLOTS_USED =
      TEMPORAL_METRICS_PREFIX + "worker_task_slots_used";

  //
  // Nexus Worker
  //
  @Experimental
  public static final String NEXUS_POLL_NO_TASK_COUNTER =
      TEMPORAL_METRICS_PREFIX + "nexus_poll_no_task";

  @Experimental
  public static final String NEXUS_SCHEDULE_TO_START_LATENCY =
      TEMPORAL_METRICS_PREFIX + "nexus_task_schedule_to_start_latency";

  @Experimental
  public static final String NEXUS_EXEC_LATENCY =
      TEMPORAL_METRICS_PREFIX + "nexus_task_execution_latency";

  @Experimental
  public static final String NEXUS_EXEC_FAILED_COUNTER =
      TEMPORAL_METRICS_PREFIX + "nexus_task_execution_failed";

  @Experimental
  public static final String NEXUS_TASK_E2E_LATENCY =
      TEMPORAL_METRICS_PREFIX + "nexus_task_endtoend_latency";

  //
  // Worker Factory
  //

  // tagged with namespace, task_queue, worker_type, workflow_type
  public static final String STICKY_CACHE_HIT = TEMPORAL_METRICS_PREFIX + "sticky_cache_hit";
  // tagged with namespace, task_queue, worker_type, workflow_type
  public static final String STICKY_CACHE_MISS = TEMPORAL_METRICS_PREFIX + "sticky_cache_miss";
  // tagged with namespace, task_queue, worker_type, workflow_type
  @Deprecated
  // This metric in its current form is useless, it's not possible for users to interpret it for any
  // meaningful purpose.
  // We count in workflows that are getting evicted because we are out of threads in workflow thread
  // pool. (makes sense)
  // We count in workflows that are getting "evicted" because a full history from the server is
  // received. (kinda makes sense)
  // We count in workflows that are getting "evicted" because they are done. But only if they were
  // added to the cache. (doesn't make sense)
  // We DON'T count in workflows that are getting "evicted" because the cache is overflown. (doesn't
  // make sense)
  // TODO revisit implementation of this metric in Go and if it make sense there, fix the Java
  // version.
  //  Otherwise deprecate it everywhere and remove from docs.
  public static final String STICKY_CACHE_TOTAL_FORCED_EVICTION =
      TEMPORAL_METRICS_PREFIX + "sticky_cache_total_forced_eviction";
  // tagged with namespace, task_queue, worker_type, workflow_type
  public static final String STICKY_CACHE_THREAD_FORCED_EVICTION =
      TEMPORAL_METRICS_PREFIX + "sticky_cache_thread_forced_eviction";
  // gauge, tagged with namespace
  public static final String STICKY_CACHE_SIZE = TEMPORAL_METRICS_PREFIX + "sticky_cache_size";
  // gauge
  public static final String WORKFLOW_ACTIVE_THREAD_COUNT =
      TEMPORAL_METRICS_PREFIX + "workflow_active_thread_count";

  //
  // Resource tuner
  //
  // Tagged with namespace & task_queue
  public static final String RESOURCE_MEM_USAGE = "resource_slots_mem_usage";
  public static final String RESOURCE_CPU_USAGE = "resource_slots_cpu_usage";
  public static final String RESOURCE_MEM_PID = "resource_slots_mem_pid_output";
  public static final String RESOURCE_CPU_PID = "resource_slots_cpu_pid_output";
}
