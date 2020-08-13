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

package io.temporal.internal.replay;

import io.temporal.api.common.v1.Payloads;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder;
import java.util.Optional;

/**
 * Task handler that encapsulates a cached workflow and can handle multiple calls to
 * handleWorkflowTask for the same workflow run.
 *
 * <p>Instances of this object can be cached in between workflow tasks.
 */
public interface WorkflowRunTaskHandler {

  /**
   * Handles a single workflow task.
   *
   * @param workflowTask task to handle
   * @return true if new workflow task should be force created synchronously as local activities are
   *     still running.
   */
  WorkflowTaskResult handleWorkflowTask(PollWorkflowTaskQueueResponseOrBuilder workflowTask);

  Optional<Payloads> handleQueryWorkflowTask(
      PollWorkflowTaskQueueResponseOrBuilder workflowTask, WorkflowQuery query);

  void close();
}
