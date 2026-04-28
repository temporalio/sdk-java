package io.temporal.client;

import io.temporal.api.workflowservice.v1.DescribeNexusOperationExecutionResponse;
import io.temporal.common.Experimental;

/** Snapshot of a standalone Nexus operation execution returned by describe/poll calls. */
@Experimental
public final class NexusClientOperationExecutionDescription {

  private final DescribeNexusOperationExecutionResponse response;

  public NexusClientOperationExecutionDescription(DescribeNexusOperationExecutionResponse response) {
    this.response = response;
  }

  /** Run ID of the operation described. */
  public String getRunId() {
    return response.getRunId();
  }

  /** Underlying proto response. Exposed while the Nexus SDK surface is still experimental. */
  public DescribeNexusOperationExecutionResponse getRawResponse() {
    return response;
  }
}
