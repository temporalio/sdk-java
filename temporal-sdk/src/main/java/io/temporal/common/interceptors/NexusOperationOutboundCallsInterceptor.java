package io.temporal.common.interceptors;

import com.uber.m3.tally.Scope;
import io.temporal.client.WorkflowClient;
import io.temporal.common.Experimental;
import io.temporal.nexus.NexusInfo;

/**
 * Can be used to intercept calls from a Nexus operation into the Temporal APIs.
 *
 * <p>Prefer extending {@link NexusOperationOutboundCallsInterceptorBase} and overriding only the
 * methods you need instead of implementing this interface directly. {@link
 * NexusOperationOutboundCallsInterceptorBase} provides correct default implementations to all the
 * methods of this interface.
 *
 * <p>An instance may be created in {@link
 * NexusOperationInboundCallsInterceptor#init(NexusOperationOutboundCallsInterceptor)} and set by
 * passing it into {@code init} method of the {@code next} {@link
 * NexusOperationInboundCallsInterceptor} The implementation must forward all the calls to the
 * outbound interceptor passed as a {@code outboundCalls} parameter to the {@code init} call.
 */
@Experimental
public interface NexusOperationOutboundCallsInterceptor {
  /** Intercepts call to get the Nexus info in a Nexus operation. */
  NexusInfo getInfo();

  /** Intercepts call to get the metric scope in a Nexus operation. */
  Scope getMetricsScope();

  /** Intercepts call to get the workflow client in a Nexus operation. */
  WorkflowClient getWorkflowClient();
}
