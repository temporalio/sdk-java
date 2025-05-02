package io.temporal.common.interceptors;

import javax.annotation.Nonnull;

/** Convenience base class for WorkflowInboundCallsInterceptor implementations. */
public class WorkflowInboundCallsInterceptorBase implements WorkflowInboundCallsInterceptor {
  private final WorkflowInboundCallsInterceptor next;

  public WorkflowInboundCallsInterceptorBase(WorkflowInboundCallsInterceptor next) {
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
    next.handleSignal(input);
  }

  @Override
  public QueryOutput handleQuery(QueryInput input) {
    return next.handleQuery(input);
  }

  @Override
  public void validateUpdate(UpdateInput input) {
    next.validateUpdate(input);
  }

  @Override
  public UpdateOutput executeUpdate(UpdateInput input) {
    return next.executeUpdate(input);
  }

  @Nonnull
  @Override
  public Object newWorkflowMethodThread(Runnable runnable, String name) {
    return next.newWorkflowMethodThread(runnable, name);
  }

  @Nonnull
  @Override
  public Object newCallbackThread(Runnable runnable, String name) {
    return next.newCallbackThread(runnable, name);
  }
}
