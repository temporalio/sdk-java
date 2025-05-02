package io.temporal.common.interceptors;

import com.uber.m3.tally.Scope;
import io.temporal.client.WorkflowClient;
import io.temporal.common.Experimental;

/** Convenience base class for {@link NexusOperationOutboundCallsInterceptor} implementations. */
@Experimental
public class NexusOperationOutboundCallsInterceptorBase
    implements NexusOperationOutboundCallsInterceptor {
  private final NexusOperationOutboundCallsInterceptor next;

  public NexusOperationOutboundCallsInterceptorBase(NexusOperationOutboundCallsInterceptor next) {
    this.next = next;
  }

  @Override
  public Scope getMetricsScope() {
    return next.getMetricsScope();
  }

  @Override
  public WorkflowClient getWorkflowClient() {
    return next.getWorkflowClient();
  }
}
