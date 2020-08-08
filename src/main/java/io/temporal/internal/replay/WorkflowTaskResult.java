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
import io.temporal.api.query.v1.WorkflowQueryResult;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class WorkflowTaskResult {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    private List<Command> commands;
    private boolean finalCommand;
    private Map<String, WorkflowQueryResult> queryResults;
    private boolean forceWorkflowTask;

    public Builder setCommands(List<Command> commands) {
      this.commands = commands;
      return this;
    }

    public Builder setFinalCommand(boolean finalCommand) {
      this.finalCommand = finalCommand;
      return this;
    }

    public Builder setQueryResults(Map<String, WorkflowQueryResult> queryResults) {
      this.queryResults = queryResults;
      return this;
    }

    public Builder setForceWorkflowTask(boolean forceWorkflowTask) {
      this.forceWorkflowTask = forceWorkflowTask;
      return this;
    }

    public WorkflowTaskResult build() {
      return new WorkflowTaskResult(
          commands == null ? Collections.emptyList() : commands,
          queryResults == null ? Collections.emptyMap() : queryResults,
          finalCommand,
          forceWorkflowTask);
    }
  }

  private final List<Command> commands;
  private final boolean finalCommand;
  private final Map<String, WorkflowQueryResult> queryResults;
  private final boolean forceWorkflowTask;

  private WorkflowTaskResult(
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
