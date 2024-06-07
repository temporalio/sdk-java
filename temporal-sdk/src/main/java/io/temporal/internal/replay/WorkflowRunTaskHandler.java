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

package io.temporal.internal.replay;

import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder;
import io.temporal.worker.NonDeterministicException;

/**
 * Task handler that encapsulates a cached workflow and can handle multiple calls to
 * handleWorkflowTask for the same workflow run.
 *
 * <p>Instances of this object can be cached in between workflow tasks.
 */
public interface WorkflowRunTaskHandler {

  /**
   * Handles a single new workflow task of the workflow.
   *
   * @param workflowTask task to handle
   * @return an object that can be used to build workflow task completion or failure response
   * @throws Throwable if processing experienced issues that are considered unrecoverable inside the
   *     current workflow task. {@link NonDeterministicException} or {@link Error} are such cases.
   */
  WorkflowTaskResult handleWorkflowTask(
      PollWorkflowTaskQueueResponseOrBuilder workflowTask, WorkflowHistoryIterator historyIterator)
      throws Throwable;

  /**
   * Handles a Direct Query (or Legacy Query) scenario. In this case, it's not a real workflow task
   * and the processing can't generate any new commands.
   *
   * @param workflowTask task to handle
   * @return an object that can be used to build a legacy query response
   * @throws Throwable if processing experienced issues that are considered unrecoverable inside the
   *     current workflow task. {@link NonDeterministicException} or {@link Error} are such cases.
   */
  QueryResult handleDirectQueryWorkflowTask(
      PollWorkflowTaskQueueResponseOrBuilder workflowTask, WorkflowHistoryIterator historyIterator)
      throws Throwable;

  /**
   * Reset the workflow event ID.
   *
   * @param eventId the event ID to reset the cached state to.
   */
  void resetStartedEvenId(Long eventId);

  void close();
}
