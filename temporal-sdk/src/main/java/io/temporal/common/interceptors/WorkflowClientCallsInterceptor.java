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
import io.temporal.common.Experimental;

@Experimental
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

  final class WorkflowSignalInput {
    private final String workflowId;
    private final String signalName;
    private final Object[] arguments;

    public WorkflowSignalInput(String workflowId, String signalName, Object[] signalArguments) {
      if (workflowId == null) {
        throw new IllegalArgumentException("workflowId should be specified for signal call");
      }
      this.workflowId = workflowId;
      if (signalName == null) {
        throw new IllegalArgumentException("signalName should be specified for signal call");
      }
      this.signalName = signalName;
      this.arguments = signalArguments;
    }

    public String getWorkflowId() {
      return workflowId;
    }

    public String getSignalName() {
      return signalName;
    }

    public Object[] getArguments() {
      return arguments;
    }
  }

  final class WorkflowStartWithSignalInput {
    private final WorkflowStartInput workflowStartInput;
    // TODO Spikhalskiy I'm not sure about this structure.
    // SignalWithStartWorkflowExecutionParameters is
    // StartWorkflowExecutionRequest + signalName + signalInput,
    // not StartWorkflowExecutionRequest + SignalWorkflowExecutionRequest
    private final WorkflowSignalInput workflowSignalInput;

    public WorkflowStartWithSignalInput(
        WorkflowStartInput workflowStartInput, WorkflowSignalInput workflowSignalInput) {
      this.workflowStartInput = workflowStartInput;
      this.workflowSignalInput = workflowSignalInput;
    }

    public WorkflowStartInput getWorkflowStartInput() {
      return workflowStartInput;
    }

    public WorkflowSignalInput getWorkflowSignalInput() {
      return workflowSignalInput;
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

  void signal(WorkflowSignalInput input);

  WorkflowStartOutput signalWithStart(WorkflowStartWithSignalInput input);
}
