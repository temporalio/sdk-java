package io.temporal.internal.replay;

import io.temporal.api.common.v1.Payloads;
import java.util.Optional;

public class QueryResult {
  private final Optional<Payloads> responsePayloads;
  private final boolean workflowMethodCompleted;

  public QueryResult(Optional<Payloads> responsePayloads, boolean workflowMethodCompleted) {
    this.responsePayloads = responsePayloads;
    this.workflowMethodCompleted = workflowMethodCompleted;
  }

  public Optional<Payloads> getResponsePayloads() {
    return responsePayloads;
  }

  public boolean isWorkflowMethodCompleted() {
    return workflowMethodCompleted;
  }
}
