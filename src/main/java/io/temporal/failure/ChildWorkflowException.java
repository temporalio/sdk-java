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

package io.temporal.failure;

import io.temporal.proto.common.WorkflowExecution;
import io.temporal.proto.common.WorkflowType;
import io.temporal.proto.failure.ChildWorkflowExecutionFailureInfo;
import io.temporal.proto.failure.Failure;

public final class ChildWorkflowException extends RemoteException {

  private final long initiatedEventId;
  private final long startedEventId;
  private final WorkflowType workflowType;
  private final WorkflowExecution workflowExecution;
  private final String namespace;

  public ChildWorkflowException(Failure failure, Exception cause) {
    super(toString(failure), failure, cause);
    ChildWorkflowExecutionFailureInfo info = failure.getChildWorkflowExecutionFailureInfo();
    this.initiatedEventId = info.getInitiatedEventId();
    this.startedEventId = info.getStartedEventId();
    this.workflowType = info.getWorkflowType();
    this.workflowExecution = info.getWorkflowExecution();
    this.namespace = info.getNamespace();
  }

  public long getInitiatedEventId() {
    return initiatedEventId;
  }

  public long getStartedEventId() {
    return startedEventId;
  }

  public WorkflowType getWorkflowType() {
    return workflowType;
  }

  public WorkflowExecution getWorkflowExecution() {
    return workflowExecution;
  }

  public String getNamespace() {
    return namespace;
  }

  private static String toString(Failure failure) {
    if (!failure.hasChildWorkflowExecutionFailureInfo()) {
      throw new IllegalArgumentException(
          "Activity failure expected: " + failure.getFailureInfoCase());
    }
    ChildWorkflowExecutionFailureInfo info = failure.getChildWorkflowExecutionFailureInfo();

    return "initiatedEventId="
        + info.getInitiatedEventId()
        + ", startedEventId="
        + info.getStartedEventId()
        + ", workflowType="
        + info.getWorkflowType()
        + ", workflowExecution="
        + info.getWorkflowExecution()
        + ", namespace='"
        + info.getNamespace()
        + '\'';
  }

  @Override
  public String toString() {
    return "ChildWorkflowException{" + toString(failure) + '}';
  }
}
