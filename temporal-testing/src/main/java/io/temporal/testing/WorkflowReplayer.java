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

package io.temporal.testing;

import com.google.common.collect.ObjectArrays;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.worker.Worker;
import java.io.File;

/** Replays a workflow given its history. Useful for backwards compatibility testing. */
public final class WorkflowReplayer {

  /**
   * Replays workflow from a resource that contains a json serialized history.
   *
   * @param resourceName name of the resource
   * @param workflowClass workflow implementation class to replay
   * @param moreWorkflowClasses optional additional workflow implementation classes
   * @throws Exception if replay failed for any reason.
   */
  public static void replayWorkflowExecutionFromResource(
      String resourceName, Class<?> workflowClass, Class<?>... moreWorkflowClasses)
      throws Exception {
    WorkflowExecutionHistory history = WorkflowHistoryLoader.readHistoryFromResource(resourceName);
    replayWorkflowExecution(history, workflowClass, moreWorkflowClasses);
  }

  /**
   * Replays workflow from a resource that contains a json serialized history.
   *
   * @param resourceName name of the resource.
   * @param worker worker existing worker with the correct task queue and registered
   *     implementations.
   * @throws Exception if replay failed for any reason.
   */
  public static void replayWorkflowExecutionFromResource(String resourceName, Worker worker)
      throws Exception {
    WorkflowExecutionHistory history = WorkflowHistoryLoader.readHistoryFromResource(resourceName);
    replayWorkflowExecution(history, worker);
  }

  /**
   * Replays workflow from a resource that contains a json serialized history.
   *
   * @param resourceName name of the resource.
   * @param testWorkflowEnvironment to be used to create a worker on a task queue.
   * @param workflowClass s workflow implementation class to replay
   * @param moreWorkflowClasses optional additional workflow implementation classes
   * @throws Exception if replay failed for any reason.
   */
  public static void replayWorkflowExecutionFromResource(
      String resourceName,
      TestWorkflowEnvironment testWorkflowEnvironment,
      Class<?> workflowClass,
      Class<?>... moreWorkflowClasses)
      throws Exception {
    WorkflowExecutionHistory history = WorkflowHistoryLoader.readHistoryFromResource(resourceName);
    replayWorkflowExecution(history, testWorkflowEnvironment, workflowClass, moreWorkflowClasses);
  }

  /**
   * Replays workflow from a file
   *
   * @param historyFile file that contains a json serialized history.
   * @param workflowClass s workflow implementation class to replay
   * @param moreWorkflowClasses optional additional workflow implementation classes
   * @throws Exception if replay failed for any reason.
   */
  public static void replayWorkflowExecution(
      File historyFile, Class<?> workflowClass, Class<?>... moreWorkflowClasses) throws Exception {
    WorkflowExecutionHistory history = WorkflowHistoryLoader.readHistory(historyFile);
    replayWorkflowExecution(history, workflowClass, moreWorkflowClasses);
  }

  /**
   * Replays workflow from a json serialized history. The json should be in the format:
   *
   * <pre>
   * {
   *   "workflowId": "...",
   *   "runId": "...",
   *   "events": [
   *     ...
   *   ]
   * }
   * </pre>
   *
   * RunId <b>must</b> match the one used to generate the serialized history.
   *
   * @param jsonSerializedHistory string that contains the json serialized history.
   * @param workflowClass s workflow implementation class to replay
   * @param moreWorkflowClasses optional additional workflow implementation classes
   * @throws Exception if replay failed for any reason.
   */
  public static void replayWorkflowExecution(
      String jsonSerializedHistory, Class<?> workflowClass, Class<?>... moreWorkflowClasses)
      throws Exception {
    WorkflowExecutionHistory history = WorkflowExecutionHistory.fromJson(jsonSerializedHistory);
    replayWorkflowExecution(history, workflowClass, moreWorkflowClasses);
  }

  /**
   * Replays workflow from a {@link WorkflowExecutionHistory}. RunId <b>must</b> match the one used
   * to generate the serialized history.
   *
   * @param history object that contains the workflow ids and the events.
   * @param workflowClass s workflow implementation class to replay
   * @param moreWorkflowClasses optional additional workflow implementation classes
   * @throws Exception if replay failed for any reason.
   */
  @SuppressWarnings("deprecation")
  public static void replayWorkflowExecution(
      io.temporal.internal.common.WorkflowExecutionHistory history,
      Class<?> workflowClass,
      Class<?>... moreWorkflowClasses)
      throws Exception {
    TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance();
    try {
      replayWorkflowExecution(history, testEnv, workflowClass, moreWorkflowClasses);
    } finally {
      testEnv.close();
    }
  }

  /**
   * Replays workflow from a {@link WorkflowExecutionHistory}.
   *
   * @param history object that contains the workflow ids and the events.
   * @param testWorkflowEnvironment to be used to create a worker on a task queue.
   * @param workflowClass s workflow implementation class to replay
   * @param moreWorkflowClasses optional additional workflow implementation classes
   * @throws Exception if replay failed for any reason.
   */
  @SuppressWarnings("deprecation")
  public static void replayWorkflowExecution(
      io.temporal.internal.common.WorkflowExecutionHistory history,
      TestWorkflowEnvironment testWorkflowEnvironment,
      Class<?> workflowClass,
      Class<?>... moreWorkflowClasses)
      throws Exception {
    Worker worker = testWorkflowEnvironment.newWorker(getQueueName((history)));
    worker.registerWorkflowImplementationTypes(
        ObjectArrays.concat(moreWorkflowClasses, workflowClass));
    replayWorkflowExecution(history, worker);
  }

  /**
   * Replays workflow from a resource that contains a json serialized history.
   *
   * @param history object that contains the workflow ids and the events.
   * @param worker existing worker with registered workflow implementations.
   * @throws Exception if replay failed for any reason.
   */
  @SuppressWarnings("deprecation")
  public static void replayWorkflowExecution(
      io.temporal.internal.common.WorkflowExecutionHistory history, Worker worker)
      throws Exception {
    worker.replayWorkflowExecution(history);
  }

  /**
   * Replays workflows provided by an iterable.
   *
   * @param histories The histories to be replayed
   * @param failFast If true, throws upon the first error encountered (if any) during replay. If
   *     false, all histories will be replayed and the returned object contains information about
   *     any failures.
   * @return If `failFast` is false, contains any replay failures encountered.
   * @throws Exception If replay failed and `failFast` is true.
   */
  @SuppressWarnings("deprecation")
  public static ReplayResults replayWorkflowExecutions(
      Iterable<? extends io.temporal.internal.common.WorkflowExecutionHistory> histories,
      boolean failFast,
      Class<?>... workflowClasses)
      throws Exception {
    try (TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance()) {
      Worker worker = testEnv.newWorker("replay-task-queue-name");
      worker.registerWorkflowImplementationTypes(workflowClasses);
      return replayWorkflowExecutions(histories, failFast, worker);
    }
  }

  /**
   * Replays workflows provided by an iterable using an already-initialized worker.
   *
   * @param histories The histories to be replayed
   * @param failFast If true, throws upon the first error encountered (if any) during replay. If
   *     false, all histories will be replayed and the returned object contains information about
   *     any failures.
   * @param worker A worker which should have registered all the workflow implementations which were
   *     used to produce (or are expected to be compatible with) the provided histories.
   * @return If `failFast` is false, contains any replay failures encountered.
   * @throws Exception If replay failed and `failFast` is true.
   */
  @SuppressWarnings("deprecation")
  public static ReplayResults replayWorkflowExecutions(
      Iterable<? extends io.temporal.internal.common.WorkflowExecutionHistory> histories,
      boolean failFast,
      Worker worker)
      throws Exception {
    ReplayResults results = new ReplayResults();
    for (io.temporal.internal.common.WorkflowExecutionHistory history : histories) {
      try {
        replayWorkflowExecution(history, worker);
      } catch (Exception e) {
        if (!failFast) {
          results.addError(history.getWorkflowExecution().getWorkflowId(), e);
        } else {
          throw e;
        }
      }
    }
    return results;
  }

  @SuppressWarnings("deprecation")
  private static String getQueueName(io.temporal.internal.common.WorkflowExecutionHistory history) {
    WorkflowExecutionStartedEventAttributes attr =
        history.getEvents().get(0).getWorkflowExecutionStartedEventAttributes();
    TaskQueue taskQueue = attr.getTaskQueue();
    return taskQueue.getName();
  }
}
