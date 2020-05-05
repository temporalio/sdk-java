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

import io.temporal.proto.common.Payloads;
import io.temporal.proto.query.QueryConsistencyLevel;
import io.temporal.proto.query.QueryRejectCondition;

public class QueryWorkflowParameters implements Cloneable {

  private Payloads input;

  private String runId;

  private String queryType;

  private String workflowId;

  private QueryRejectCondition queryRejectCondition;

  private QueryConsistencyLevel queryConsistencyLevel;

  public QueryWorkflowParameters() {}

  public Payloads getInput() {
    return input;
  }

  public void setInput(Payloads input) {
    this.input = input;
  }

  public QueryWorkflowParameters withInput(Payloads input) {
    this.input = input;
    return this;
  }

  public String getRunId() {
    return runId;
  }

  public void setRunId(String runId) {
    this.runId = runId;
  }

  public QueryWorkflowParameters withRunId(String runId) {
    this.runId = runId;
    return this;
  }

  public String getQueryType() {
    return queryType;
  }

  public void setQueryType(String queryType) {
    this.queryType = queryType;
  }

  public QueryWorkflowParameters withQueryType(String queryType) {
    this.queryType = queryType;
    return this;
  }

  public String getWorkflowId() {
    return workflowId;
  }

  public void setWorkflowId(String workflowId) {
    this.workflowId = workflowId;
  }

  public QueryWorkflowParameters withWorkflowId(String workflowId) {
    this.workflowId = workflowId;
    return this;
  }

  public QueryConsistencyLevel getQueryConsistencyLevel() {
    return queryConsistencyLevel;
  }

  public void setQueryConsistencyLevel(QueryConsistencyLevel queryConsistencyLevel) {
    this.queryConsistencyLevel = queryConsistencyLevel;
  }

  public QueryWorkflowParameters withQueryConsistencyLevel(
      QueryConsistencyLevel queryConsistencyLevel) {
    this.queryConsistencyLevel = queryConsistencyLevel;
    return this;
  }

  public QueryRejectCondition getQueryRejectCondition() {
    if (queryRejectCondition == null) {
      return QueryRejectCondition.None;
    }
    return queryRejectCondition;
  }

  public void setQueryRejectCondition(QueryRejectCondition queryRejectCondition) {
    this.queryRejectCondition = queryRejectCondition;
  }

  public QueryWorkflowParameters withQueryRejectCondition(
      QueryRejectCondition queryRejectCondition) {
    this.queryRejectCondition = queryRejectCondition;
    return this;
  }

  public QueryWorkflowParameters copy() {
    QueryWorkflowParameters result = new QueryWorkflowParameters();
    result.setInput(input);
    result.setRunId(runId);
    result.setQueryType(queryType);
    result.setWorkflowId(workflowId);
    result.setQueryRejectCondition(queryRejectCondition);
    result.setQueryConsistencyLevel(queryConsistencyLevel);
    return result;
  }

  @Override
  public String toString() {
    return "QueryWorkflowParameters{"
        + "input="
        + input
        + ", runId='"
        + runId
        + '\''
        + ", queryType='"
        + queryType
        + '\''
        + ", workflowId='"
        + workflowId
        + '\''
        + ", queryRejectCondition="
        + queryRejectCondition
        + ", queryConsistencyLevel="
        + queryConsistencyLevel
        + '}';
  }
}
