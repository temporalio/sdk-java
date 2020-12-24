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

package io.temporal.internal.sync;

import io.temporal.api.common.v1.Payloads;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.UntypedWorkflow;
import java.lang.reflect.InvocationTargetException;
import java.util.Optional;

class UntypedSyncWorkflowDefinition implements SyncWorkflowDefinition {

  private final Class<? extends UntypedWorkflow> workflowImplementationClass;
  private final WorkflowInterceptor[] workflowInterceptors;
  private final DataConverter dataConverter;
  private WorkflowInboundCallsInterceptor workflowInvoker;
  private UntypedWorkflow workflow;

  public UntypedSyncWorkflowDefinition(
      Class<? extends UntypedWorkflow> workflowImplementationClass,
      WorkflowInterceptor[] workflowInterceptors,
      DataConverter dataConverter) {
    this.workflowImplementationClass = workflowImplementationClass;
    this.workflowInterceptors = workflowInterceptors;
    this.dataConverter = dataConverter;
  }

  @Override
  public void initialize() {
    workflowInvoker = new RootWorkflowInboundCallsInterceptor();
    for (WorkflowInterceptor workflowInterceptor : workflowInterceptors) {
      workflowInvoker = workflowInterceptor.interceptWorkflow(workflowInvoker);
    }
    workflowInvoker.init(WorkflowInternal.getRootWorkflowContext());
  }

  @Override
  public Optional<Payloads> execute(Optional<Payloads> input) {
    return (Optional<Payloads>) workflowInvoker.execute(new Object[] {input});
  }

  private class RootWorkflowInboundCallsInterceptor implements WorkflowInboundCallsInterceptor {
    @Override
    public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
      WorkflowInternal.getRootWorkflowContext().setHeadInterceptor(outboundCalls);
      newInstance();
      WorkflowInternal.registerListener(workflow);
    }

    @Override
    public Object execute(Object[] arguments) {
      return workflow.execute((Optional<Payloads>) arguments[0], dataConverter);
    }

    @Override
    public void processSignal(String signalName, Object[] arguments, long EventId) {
      throw new UnsupportedOperationException(
          "Signals are delivered through Workflow.registerListener");
    }

    private void newInstance() {
      if (workflow != null) {
        throw new IllegalStateException("Already called");
      }
      try {
        workflow = workflowImplementationClass.getDeclaredConstructor().newInstance();
      } catch (NoSuchMethodException
          | InstantiationException
          | IllegalAccessException
          | InvocationTargetException e) {
        // Error to fail workflow task as this can be fixed by a new deployment.
        throw new Error(
            "Failure instantiating workflow implementation class "
                + workflowImplementationClass.getName(),
            e);
      }
    }
  }
}
