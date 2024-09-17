/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.sync;

import io.temporal.api.common.v1.Payloads;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.converter.Values;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.DynamicWorkflow;
import io.temporal.workflow.Functions;
import java.util.Optional;

final class DynamicSyncWorkflowDefinition implements SyncWorkflowDefinition {

  private final Functions.Func1<EncodedValues, ? extends DynamicWorkflow> factory;
  private final WorkerInterceptor[] workerInterceptors;
  // don't pass it down to other classes, it's a "cached" instance for internal usage only
  private final DataConverter dataConverterWithWorkflowContext;
  private WorkflowInboundCallsInterceptor workflowInvoker;

  public DynamicSyncWorkflowDefinition(
      Functions.Func1<EncodedValues, ? extends DynamicWorkflow> factory,
      WorkerInterceptor[] workerInterceptors,
      DataConverter dataConverterWithWorkflowContext) {
    this.factory = factory;
    this.workerInterceptors = workerInterceptors;
    this.dataConverterWithWorkflowContext = dataConverterWithWorkflowContext;
  }

  @Override
  public void initialize(Optional<Payloads> input) {
    SyncWorkflowContext workflowContext = WorkflowInternal.getRootWorkflowContext();
    workflowInvoker = new RootWorkflowInboundCallsInterceptor(workflowContext, input);
    for (WorkerInterceptor workerInterceptor : workerInterceptors) {
      workflowInvoker = workerInterceptor.interceptWorkflow(workflowInvoker);
    }
    workflowContext.initHeadInboundCallsInterceptor(workflowInvoker);
    workflowInvoker.init(workflowContext);
  }

  @Override
  public Optional<Payloads> execute(Header header, Optional<Payloads> input) {
    Values args = new EncodedValues(input, dataConverterWithWorkflowContext);
    WorkflowInboundCallsInterceptor.WorkflowOutput result =
        workflowInvoker.execute(
            new WorkflowInboundCallsInterceptor.WorkflowInput(header, new Object[] {args}));
    return dataConverterWithWorkflowContext.toPayloads(result.getResult());
  }

  class RootWorkflowInboundCallsInterceptor extends BaseRootWorkflowInboundCallsInterceptor {
    private DynamicWorkflow workflow;
    private Optional<Payloads> input;

    public RootWorkflowInboundCallsInterceptor(
        SyncWorkflowContext workflowContext, Optional<Payloads> input) {
      super(workflowContext);
      this.input = input;
    }

    @Override
    public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
      super.init(outboundCalls);
      newInstance(input);
      WorkflowInternal.registerListener(workflow);
    }

    @Override
    public WorkflowOutput execute(WorkflowInput input) {
      Object result = workflow.execute((EncodedValues) input.getArguments()[0]);
      return new WorkflowOutput(result);
    }

    private void newInstance(Optional<Payloads> input) {
      if (workflow != null) {
        throw new IllegalStateException("Already called");
      }
      workflow = factory.apply(new EncodedValues(input, dataConverterWithWorkflowContext));
    }
  }
}
