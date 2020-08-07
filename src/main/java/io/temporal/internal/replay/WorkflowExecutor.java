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

import io.temporal.api.command.v1.Command;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.api.query.v1.WorkflowQueryResult;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface WorkflowExecutor {

  /**
   * Handles a single workflow task.
   *
   * @param workflowTask task to handle
   * @return true if new task should be created synchronously as local activities are still running.
   */
  WorkflowTaskResult handleWorkflowTask(PollWorkflowTaskQueueResponseOrBuilder workflowTask);

  Optional<Payloads> handleQueryWorkflowTask(
      PollWorkflowTaskQueueResponseOrBuilder workflowTask, WorkflowQuery query);

  void close();

  Duration getWorkflowTaskTimeout();

  class WorkflowTaskResult {

    private final List<Command> commands;
    private final boolean finalCommand;
    private final Map<String, WorkflowQueryResult> queryResults;
    private final boolean forceWorkflowTask;

    public WorkflowTaskResult(
        List<Command> commands,
        Map<String, WorkflowQueryResult> queryResults,
        boolean finalCommand,
        boolean forceWorkflowTask) {
      this.commands = commands;
      if (forceWorkflowTask && finalCommand) {
        throw new IllegalArgumentException("both forceWorkflowTask and finalCommand are true");
      }
      this.queryResults = queryResults;
      this.finalCommand = finalCommand;
      this.forceWorkflowTask = forceWorkflowTask;
    }

    public List<Command> getCommands() {
      return commands;
    }

    public Map<String, WorkflowQueryResult> getQueryResults() {
      return queryResults;
    }

    /** Is this result contain a workflow completion command */
    public boolean isFinalCommand() {
      return finalCommand;
    }

    public boolean isForceWorkflowTask() {
      return forceWorkflowTask;
    }
  }
}
