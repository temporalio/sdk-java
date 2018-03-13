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

import java.nio.charset.StandardCharsets;

public final class SignalExternalWorkflowParameters implements Cloneable {

  private String domain;

  private byte[] input;

  private String runId;

  private String signalName;

  private String workflowId;

  public String getDomain() {
    return domain;
  }

  public SignalExternalWorkflowParameters setDomain(String domain) {
    this.domain = domain;
    return this;
  }

  public byte[] getInput() {
    return input;
  }

  public void setInput(byte[] input) {
    this.input = input;
  }

  public SignalExternalWorkflowParameters withInput(byte[] input) {
    this.input = input;
    return this;
  }

  public String getRunId() {
    return runId;
  }

  public void setRunId(String runId) {
    this.runId = runId;
  }

  public SignalExternalWorkflowParameters withRunId(String runId) {
    this.runId = runId;
    return this;
  }

  public String getSignalName() {
    return signalName;
  }

  public void setSignalName(String signalName) {
    this.signalName = signalName;
  }

  public SignalExternalWorkflowParameters withSignalName(String signalName) {
    this.signalName = signalName;
    return this;
  }

  public String getWorkflowId() {
    return workflowId;
  }

  public void setWorkflowId(String workflowId) {
    this.workflowId = workflowId;
  }

  public SignalExternalWorkflowParameters withWorkflowId(String workflowId) {
    this.workflowId = workflowId;
    return this;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    sb.append("SignalName: " + signalName + ", ");
    sb.append("Input: " + new String(input, 0, 512, StandardCharsets.UTF_8) + ", ");
    sb.append("WorkflowId: " + workflowId + ", ");
    sb.append("RunId: " + runId + ", ");
    sb.append("}");
    return sb.toString();
  }

  public SignalExternalWorkflowParameters copy() {
    SignalExternalWorkflowParameters result = new SignalExternalWorkflowParameters();
    result.setInput(input);
    result.setRunId(runId);
    result.setSignalName(signalName);
    result.setWorkflowId(workflowId);
    return result;
  }
}
