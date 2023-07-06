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

import io.temporal.api.command.v1.Command;
import io.temporal.api.protocol.v1.Message;
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
    private List<Message> messages;
    private boolean finalCommand;
    private Map<String, WorkflowQueryResult> queryResults;
    private boolean forceWorkflowTask;
    private int nonfirstLocalActivityAttempts;
    private List<Integer> sdkFlags;

    public Builder setCommands(List<Command> commands) {
      this.commands = commands;
      return this;
    }

    public Builder setMessages(List<Message> messages) {
      this.messages = messages;
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

    public Builder setNonfirstLocalActivityAttempts(int nonfirstLocalActivityAttempts) {
      this.nonfirstLocalActivityAttempts = nonfirstLocalActivityAttempts;
      return this;
    }

    public Builder setSdkFlags(List<Integer> sdkFlags) {
      this.sdkFlags = sdkFlags;
      return this;
    }

    public WorkflowTaskResult build() {
      return new WorkflowTaskResult(
          commands == null ? Collections.emptyList() : commands,
          messages == null ? Collections.emptyList() : messages,
          queryResults == null ? Collections.emptyMap() : queryResults,
          finalCommand,
          forceWorkflowTask,
          nonfirstLocalActivityAttempts,
          sdkFlags == null ? Collections.emptyList() : sdkFlags);
    }
  }

  private final List<Command> commands;
  private final List<Message> messages;
  private final boolean finalCommand;
  private final Map<String, WorkflowQueryResult> queryResults;
  private final boolean forceWorkflowTask;
  private final int nonfirstLocalActivityAttempts;
  private final List<Integer> sdkFlags;

  private WorkflowTaskResult(
      List<Command> commands,
      List<Message> messages,
      Map<String, WorkflowQueryResult> queryResults,
      boolean finalCommand,
      boolean forceWorkflowTask,
      int nonfirstLocalActivityAttempts,
      List<Integer> sdkFlags) {
    this.commands = commands;
    this.messages = messages;
    this.nonfirstLocalActivityAttempts = nonfirstLocalActivityAttempts;
    if (forceWorkflowTask && finalCommand) {
      throw new IllegalArgumentException("both forceWorkflowTask and finalCommand are true");
    }
    this.queryResults = queryResults;
    this.finalCommand = finalCommand;
    this.forceWorkflowTask = forceWorkflowTask;
    this.sdkFlags = sdkFlags;
  }

  public List<Command> getCommands() {
    return commands;
  }

  public List<Message> getMessages() {
    return messages;
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

  public int getNonfirstLocalActivityAttempts() {
    return nonfirstLocalActivityAttempts;
  }

  public List<Integer> getSdkFlags() {
    return sdkFlags;
  }
}
