package io.temporal.internal.context;

import io.temporal.activity.ActivityExecutionContext;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;

public class ContextPropagatorWorkflowInterceptor implements WorkerInterceptor {
  @Override
  public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
    return new ContextPropagatorWorkflowInboundCallsInterceptor(next);
  }

  @Override
  public ActivityInboundCallsInterceptor interceptActivity(ActivityInboundCallsInterceptor next) {
    return new ContextPropagatorActivityInboundCallsInterceptor(next);
  }

  private static class ContextPropagatorWorkflowInboundCallsInterceptor
      implements WorkflowInboundCallsInterceptor {
    private final WorkflowInboundCallsInterceptor next;

    public ContextPropagatorWorkflowInboundCallsInterceptor(WorkflowInboundCallsInterceptor next) {
      this.next = next;
    }

    @Override
    public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
      next.init(outboundCalls);
    }

    @Override
    public WorkflowOutput execute(WorkflowInput input) {
      return next.execute(input);
    }

    @Override
    public void handleSignal(SignalInput input) {
      handleSignal(input);
    }

    @Override
    public QueryOutput handleQuery(QueryInput input) {
      return next.handleQuery(input);
    }
  }

  private static class ContextPropagatorActivityInboundCallsInterceptor
      implements ActivityInboundCallsInterceptor {
    private final ActivityInboundCallsInterceptor next;

    private ContextPropagatorActivityInboundCallsInterceptor(ActivityInboundCallsInterceptor next) {
      this.next = next;
    }

    @Override
    public void init(ActivityExecutionContext context) {
      next.init(context);
    }

    @Override
    public Object execute(Object[] arguments) {
      return next.execute(arguments);
    }
  }
  ;
}
