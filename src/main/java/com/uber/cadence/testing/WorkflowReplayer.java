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

package com.uber.cadence.testing;

import com.google.common.collect.ObjectArrays;
import com.uber.cadence.TaskList;
import com.uber.cadence.WorkflowExecutionStartedEventAttributes;
import com.uber.cadence.common.WorkflowExecutionHistory;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import com.uber.cadence.worker.Worker;
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
    WorkflowExecutionHistory history = WorkflowExecutionUtils.readHistoryFromResource(resourceName);
    replayWorkflowExecution(history, workflowClass, moreWorkflowClasses);
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
    WorkflowExecutionHistory history = WorkflowExecutionUtils.readHistory(historyFile);
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
  public static void replayWorkflowExecution(
      WorkflowExecutionHistory history, Class<?> workflowClass, Class<?>... moreWorkflowClasses)
      throws Exception {
    WorkflowExecutionStartedEventAttributes attr =
        history.getEvents().get(0).getWorkflowExecutionStartedEventAttributes();
    TaskList taskList = attr.getTaskList();
    TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance();
    Worker worker = testEnv.newWorker(taskList.getName());
    worker.registerWorkflowImplementationTypes(
        ObjectArrays.concat(moreWorkflowClasses, workflowClass));
    worker.replayWorkflowExecution(history);
  }
}
