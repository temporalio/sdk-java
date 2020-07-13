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
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface Decider {

  DecisionResult decide(PollWorkflowTaskQueueResponseOrBuilder workflowTask) throws Throwable;

  Optional<Payloads> query(PollWorkflowTaskQueueResponseOrBuilder workflowTask, WorkflowQuery query)
      throws Throwable;

  void close();

  class DecisionResult {
    private final List<Command> decisions;
    private final boolean forceCreateNewWorkflowTask;
    private final boolean finalDecision;
    private final Map<String, WorkflowQueryResult> queryResults;

    public DecisionResult(
        List<Command> decisions,
        Map<String, WorkflowQueryResult> queryResults,
        boolean forceCreateNewWorkflowTask,
        boolean finalDecision) {
      this.decisions = decisions;
      this.queryResults = queryResults;
      this.forceCreateNewWorkflowTask = forceCreateNewWorkflowTask;
      this.finalDecision = finalDecision;
    }

    public List<Command> getDecisions() {
      return decisions;
    }

    public boolean getForceCreateNewWorkflowTask() {
      return forceCreateNewWorkflowTask;
    }

    public Map<String, WorkflowQueryResult> getQueryResults() {
      return queryResults;
    }

    /** Is this result contain a workflow completion decision */
    public boolean isFinalDecision() {
      return finalDecision;
    }
  }
}
