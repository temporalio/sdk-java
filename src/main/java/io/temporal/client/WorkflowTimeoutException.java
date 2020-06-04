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

package io.temporal.client;

import io.temporal.proto.common.TimeoutType;
import io.temporal.proto.common.WorkflowExecution;
import java.util.Optional;

/**
 * Indicates that a workflow exceeded its execution timeout and was forcefully terminated by the
 * Temporal service.
 */
public final class WorkflowTimeoutException extends WorkflowException {

  private final TimeoutType timeoutType;

  public WorkflowTimeoutException(
      WorkflowExecution execution, Optional<String> workflowType, TimeoutType timeoutType) {
    super(timeoutType + " timeout type", execution, workflowType, null);
    this.timeoutType = timeoutType;
  }

  public TimeoutType getTimeoutType() {
    return timeoutType;
  }
}
