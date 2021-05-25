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

public class StartSpanContext {

  private OpenTracingOptions options;
  private SpanOperationType spanOperationType;
  private String typeName;
  private String workflowId;
  private String runId;
  private String parentWorkflowId;
  private String parentRunId;

  public StartSpanContext(
      OpenTracingOptions options,
      SpanOperationType spanOperationType,
      String typeName,
      String workflowId,
      String runId,
      String parentWorkflowId,
      String parentRunId) {
    this.options = options;
    this.spanOperationType = spanOperationType;
    this.typeName = typeName;
    this.workflowId = workflowId;
    this.runId = runId;
    this.parentWorkflowId = parentWorkflowId;
    this.parentRunId = parentRunId;
  }

  public OpenTracingOptions getOptions() {
    return options;
  }

  public SpanOperationType getSpanOperationType() {
    return spanOperationType;
  }

  public String getTypeName() {
    return typeName;
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
}
