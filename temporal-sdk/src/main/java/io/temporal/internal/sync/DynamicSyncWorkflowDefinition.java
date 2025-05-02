package io.temporal.internal.sync;

import io.temporal.api.common.v1.Payloads;
import io.temporal.common.VersioningBehavior;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.converter.Values;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.DynamicWorkflow;
import io.temporal.workflow.Functions;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

final class DynamicSyncWorkflowDefinition implements SyncWorkflowDefinition {

  private final Functions.Func1<EncodedValues, ? extends DynamicWorkflow> factory;
  private RootWorkflowInboundCallsInterceptor rootWorkflowInvoker;
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
    RootWorkflowInboundCallsInterceptor rootWorkflowInvoker =
        new RootWorkflowInboundCallsInterceptor(workflowContext, input);
    this.rootWorkflowInvoker = rootWorkflowInvoker;
    workflowInvoker = rootWorkflowInvoker;
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

  @Nullable
  @Override
  public Object getInstance() {
    Objects.requireNonNull(rootWorkflowInvoker, "getInstance called before initialize.");
    return rootWorkflowInvoker.getInstance();
  }

  @Override
  public VersioningBehavior getVersioningBehavior() {
    if (rootWorkflowInvoker == null || rootWorkflowInvoker.workflow == null) {
      return VersioningBehavior.UNSPECIFIED;
    }
    return rootWorkflowInvoker.workflow.getVersioningBehavior();
  }

  class RootWorkflowInboundCallsInterceptor extends BaseRootWorkflowInboundCallsInterceptor {
    private DynamicWorkflow workflow;
    private Optional<Payloads> input;

    public RootWorkflowInboundCallsInterceptor(
        SyncWorkflowContext workflowContext, Optional<Payloads> input) {
      super(workflowContext);
      this.input = input;
    }

    public DynamicWorkflow getInstance() {
      return workflow;
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
