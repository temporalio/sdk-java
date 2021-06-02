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

  private final SpanOperationType spanOperationType;
  private final String actionName;
  private final String workflowId;
  private final String runId;
  private final String parentWorkflowId;
  private final String parentRunId;

  private SpanCreationContext(
      SpanOperationType spanOperationType,
      String actionName,
      String workflowId,
      String runId,
      String parentWorkflowId,
      String parentRunId) {
    this.spanOperationType = spanOperationType;
    this.actionName = actionName;
    this.workflowId = workflowId;
    this.runId = runId;
    this.parentWorkflowId = parentWorkflowId;
    this.parentRunId = parentRunId;
  }

  public SpanOperationType getSpanOperationType() {
    return spanOperationType;
  }

  /**
   * Returns the action name, which is the name of the Workflow or Activity class
   *
   * @return The action name
   */
  public String getActionName() {
    return actionName;
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
    private String actionName;
    private String workflowId;
    private String runId;
    private String parentWorkflowId;
    private String parentRunId;

    private Builder() {}

    public Builder setSpanOperationType(SpanOperationType spanOperationType) {
      this.spanOperationType = spanOperationType;
      return this;
    }

    public Builder setActionName(String actionName) {
      this.actionName = actionName;
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
          spanOperationType, actionName, workflowId, runId, parentWorkflowId, parentRunId);
    }
  }
}
