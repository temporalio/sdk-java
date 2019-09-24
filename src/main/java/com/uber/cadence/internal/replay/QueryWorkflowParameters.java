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

package com.uber.cadence.internal.replay;

import com.uber.cadence.QueryRejectCondition;
import java.nio.charset.StandardCharsets;

public class QueryWorkflowParameters implements Cloneable {

  private byte[] input;

  private String runId;

  private String queryType;

  private String workflowId;

  private QueryRejectCondition queryRejectCondition;

  public QueryWorkflowParameters() {}

  public byte[] getInput() {
    return input;
  }

  public void setInput(byte[] input) {
    this.input = input;
  }

  public QueryWorkflowParameters withInput(byte[] input) {
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

  public QueryRejectCondition getQueryRejectCondition() {
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

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    sb.append("QueryName: " + queryType + ", ");
    sb.append("Input: " + new String(input, 0, 512, StandardCharsets.UTF_8) + ", ");
    sb.append("WorkflowId: " + workflowId + ", ");
    sb.append("RunId: " + runId + ", ");
    sb.append("QueryRejectCondition: " + queryRejectCondition + ", ");
    sb.append("}");
    return sb.toString();
  }

  public QueryWorkflowParameters copy() {
    QueryWorkflowParameters result = new QueryWorkflowParameters();
    result.setInput(input);
    result.setRunId(runId);
    result.setQueryType(queryType);
    result.setWorkflowId(workflowId);
    result.setQueryRejectCondition(queryRejectCondition);
    return result;
  }
}
