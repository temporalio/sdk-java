package io.temporal.testing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ReplayResults {

  public class ReplayError {
    public final String workflowId;
    public final Exception exception;

    public ReplayError(String workflowId, Exception exception) {
      this.workflowId = workflowId;
      this.exception = exception;
    }
  }

  private final List<ReplayError> replayErrors;

  ReplayResults() {
    replayErrors = new ArrayList<>();
  }

  public Collection<ReplayError> allErrors() {
    return replayErrors;
  }

  public boolean hadAnyError() {
    return !allErrors().isEmpty();
  }

  void addError(String workflowId, Exception err) {
    replayErrors.add(new ReplayError(workflowId, err));
  }
}
