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

package io.temporal.opentracing;

/**
 * Used when creating an OpenTracing span and provides contextual information used for naming and
 * tagging OT spans.
 */
public class SpanCreationContext {

  private SpanOperationType spanOperationType;
  private String operationName;
  private String workflowId;
  private String runId;
  private String parentWorkflowId;
  private String parentRunId;

  private SpanCreationContext(
      SpanOperationType spanOperationType,
      String operationName,
      String workflowId,
      String runId,
      String parentWorkflowId,
      String parentRunId) {
    this.spanOperationType = spanOperationType;
    this.operationName = operationName;
    this.workflowId = workflowId;
    this.runId = runId;
    this.parentWorkflowId = parentWorkflowId;
    this.parentRunId = parentRunId;
  }

  public SpanOperationType getSpanOperationType() {
    return spanOperationType;
  }

  /**
   * Returns the operation name, which is the name of the Workflow or Activity class
   *
   * @return The operation name
   */
  public String getOperationName() {
    return operationName;
  }

  public String getWorkflowId() {
    return workflowId;
  }

  public String getRunId() {
    return runId;
  }

  public String getParentWorkflowId() {
    return parentWorkflowId;
  }

  public String getParentRunId() {
    return parentRunId;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    private SpanOperationType spanOperationType;
    private String operationName;
    private String workflowId;
    private String runId;
    private String parentWorkflowId;
    private String parentRunId;

    private Builder() {}

    public Builder setSpanOperationType(SpanOperationType spanOperationType) {
      this.spanOperationType = spanOperationType;
      return this;
    }

    public Builder setOperationName(String operationName) {
      this.operationName = operationName;
      return this;
    }

    public Builder setWorkflowId(String workflowId) {
      this.workflowId = workflowId;
      return this;
    }

    public Builder setRunId(String runId) {
      this.runId = runId;
      return this;
    }

    public Builder setParentWorkflowId(String parentWorkflowId) {
      this.parentWorkflowId = parentWorkflowId;
      return this;
    }

    public Builder setParentRunId(String parentRunId) {
      this.parentRunId = parentRunId;
      return this;
    }

    public SpanCreationContext build() {
      return new SpanCreationContext(
          spanOperationType, operationName, workflowId, runId, parentWorkflowId, parentRunId);
    }
  }
}
