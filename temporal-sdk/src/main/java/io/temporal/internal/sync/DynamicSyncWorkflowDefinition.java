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
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.converter.Values;
import io.temporal.common.interceptors.*;
import io.temporal.workflow.DynamicWorkflow;
import io.temporal.workflow.Functions;
import java.util.Optional;

final class DynamicSyncWorkflowDefinition implements SyncWorkflowDefinition {

  private final Functions.Func<? extends DynamicWorkflow> factory;
  private final WorkerInterceptor[] workerInterceptors;
  private final DataConverter dataConverter;
  private WorkflowInboundCallsInterceptor workflowInvoker;
  private DynamicWorkflow workflow;

  public DynamicSyncWorkflowDefinition(
      Functions.Func<? extends DynamicWorkflow> factory,
      WorkerInterceptor[] workerInterceptors,
      DataConverter dataConverter) {
    this.factory = factory;
    this.workerInterceptors = workerInterceptors;
    this.dataConverter = dataConverter;
  }

  @Override
  public void initialize() {
    SyncWorkflowContext workflowContext = WorkflowInternal.getRootWorkflowContext();

    WorkflowOutboundCallsInterceptor headOutboundInterceptor = workflowContext;
    // reverse iteration
    for (int index = workerInterceptors.length - 1; index >= 0; index--) {
      headOutboundInterceptor =
          workerInterceptors[index].interceptWorkflowOutbound(headOutboundInterceptor);
    }
    headOutboundInterceptor.init();
    workflowContext.setHeadInterceptor(headOutboundInterceptor);

    workflowInvoker = new RootWorkflowInboundCallsInterceptor(workflowContext);
    for (WorkerInterceptor workerInterceptor : workerInterceptors) {
      workflowInvoker = workerInterceptor.interceptWorkflowInbound(workflowInvoker);
    }
    workflowInvoker.init();
    workflowContext.setHeadInboundCallsInterceptor(workflowInvoker);
  }

  @Override
  public Optional<Payloads> execute(Header header, Optional<Payloads> input) {
    Values args = new EncodedValues(input, dataConverter);
    WorkflowInboundCallsInterceptor.WorkflowOutput result =
        workflowInvoker.execute(
            new WorkflowInboundCallsInterceptor.WorkflowInput(header, new Object[] {args}));
    return dataConverter.toPayloads(result.getResult());
  }

  private class RootWorkflowInboundCallsInterceptor implements WorkflowInboundCallsInterceptor {
    private final SyncWorkflowContext workflowContext;

    public RootWorkflowInboundCallsInterceptor(SyncWorkflowContext workflowContext) {
      this.workflowContext = workflowContext;
    }

    @Override
    public void init() {
      newInstance();
      WorkflowInternal.registerListener(workflow);
    }

    @Override
    public WorkflowOutput execute(WorkflowInput input) {
      Object result = workflow.execute((EncodedValues) input.getArguments()[0]);
      return new WorkflowOutput(result);
    }

    @Override
    public void handleSignal(SignalInput input) {
      workflowContext.handleInterceptedSignal(input);
    }

    @Override
    public QueryOutput handleQuery(QueryInput input) {
      return workflowContext.handleInterceptedQuery(input);
    }

    private void newInstance() {
      if (workflow != null) {
        throw new IllegalStateException("Already called");
      }
      workflow = factory.apply();
    }
  }
}
