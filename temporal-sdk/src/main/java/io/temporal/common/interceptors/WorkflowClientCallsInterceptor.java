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

package io.temporal.common.interceptors;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowOptions;

public interface WorkflowClientCallsInterceptor {
  final class WorkflowStartInput {
    private final String workflowId;
    private final String workflowType;
    private final Header header;
    private final Object[] arguments;
    private final WorkflowOptions options;

    public WorkflowStartInput(
        String workflowId,
        String workflowType,
        Header header,
        Object[] arguments,
        WorkflowOptions options) {
      if (workflowId == null) {
        throw new IllegalArgumentException("workflowId should be specified for start call");
      }
      this.workflowId = workflowId;
      if (workflowType == null) {
        throw new IllegalArgumentException("workflowType should be specified for start call");
      }
      this.workflowType = workflowType;
      this.header = header;
      this.arguments = arguments;
      if (options == null) {
        throw new IllegalArgumentException(
            "options should be specified and not be null for start call");
      }
      this.options = options;
    }

    public String getWorkflowId() {
      return workflowId;
    }

    public String getWorkflowType() {
      return workflowType;
    }

    public Header getHeader() {
      return header;
    }

    public Object[] getArguments() {
      return arguments;
    }

    public WorkflowOptions getOptions() {
      return options;
    }
  }

  final class WorkflowStartWithSignalInput {
    private final WorkflowStartInput workflowStartInput;
    private final String signalName;
    private final Object[] signalArguments;

    public WorkflowStartWithSignalInput(
        WorkflowStartInput workflowStartInput, String signalName, Object[] signalArguments) {
      this.workflowStartInput = workflowStartInput;
      this.signalName = signalName;
      this.signalArguments = signalArguments;
    }

    public WorkflowStartInput getWorkflowStartInput() {
      return workflowStartInput;
    }

    public String getSignalName() {
      return signalName;
    }

    public Object[] getSignalArguments() {
      return signalArguments;
    }
  }

  final class WorkflowStartOutput {
    private final WorkflowExecution workflowExecution;

    public WorkflowStartOutput(WorkflowExecution workflowExecution) {
      this.workflowExecution = workflowExecution;
    }

    public WorkflowExecution getWorkflowExecution() {
      return workflowExecution;
    }
  }

  WorkflowStartOutput start(WorkflowStartInput input);

  WorkflowStartOutput signalWithStart(WorkflowStartWithSignalInput input);
}
