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

public interface WorkflowStubOutboundCallsInterceptor {
  final class WorkflowInput {
    private final Header header;
    private final Object[] arguments;
    private final WorkflowOptions options;

    public WorkflowInput(Header header, Object[] arguments, WorkflowOptions options) {
      this.header = header;
      this.arguments = arguments;
      this.options = options;
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

  final class WorkflowInputWithSignal {
    private final WorkflowInput workflowInput;
    private final String signalName;
    private final Object[] signalArguments;

    public WorkflowInputWithSignal(
        WorkflowInput workflowInput, String signalName, Object[] signalArguments) {
      this.workflowInput = workflowInput;
      this.signalName = signalName;
      this.signalArguments = signalArguments;
    }

    public WorkflowInput getWorkflowInput() {
      return workflowInput;
    }

    public String getSignalName() {
      return signalName;
    }

    public Object[] getSignalArguments() {
      return signalArguments;
    }
  }

  final class WorkflowOutput {
    private final WorkflowExecution workflowExecution;

    public WorkflowOutput(WorkflowExecution workflowExecution) {
      this.workflowExecution = workflowExecution;
    }

    public WorkflowExecution getWorkflowExecution() {
      return workflowExecution;
    }
  }

  WorkflowOutput start(WorkflowInput input);

  WorkflowOutput signalWithStart(WorkflowInputWithSignal input);
}
