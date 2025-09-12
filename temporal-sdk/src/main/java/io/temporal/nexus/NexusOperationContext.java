package io.temporal.nexus;

import com.uber.m3.tally.Scope;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;

/**
 * Context object passed to a Nexus operation implementation. Use {@link
 * Nexus#getOperationContext()} from a Nexus Operation implementation to access.
 */
public interface NexusOperationContext {

  /** Get Temporal information about the Nexus Operation. */
  NexusInfo getInfo();

  /**
   * Get scope for reporting business metrics in a nexus handler. This scope is tagged with the
   * service and operation.
   *
   * <p>The original metrics scope is set through {@link
   * WorkflowServiceStubsOptions.Builder#setMetricsScope(Scope)} when a worker starts up.
   */
  Scope getMetricsScope();

  /**
   * Get a {@link WorkflowClient} that can be used to start interact with the Temporal service from
   * a Nexus handler.
   */
  WorkflowClient getWorkflowClient();
}
