package io.temporal.worker;

import io.temporal.common.Experimental;
import io.temporal.workflow.WorkflowInfo;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Input passed to {@link PreferredVersionProvider}. */
@Experimental
public final class PreferredVersionProviderInput {
  private final WorkflowInfo workflowInfo;
  private final String changeId;
  private final int minSupported;
  private final int maxSupported;

  public PreferredVersionProviderInput(
      @Nonnull WorkflowInfo workflowInfo,
      @Nonnull String changeId,
      int minSupported,
      int maxSupported) {
    this.workflowInfo = Objects.requireNonNull(workflowInfo);
    this.changeId = Objects.requireNonNull(changeId);
    this.minSupported = minSupported;
    this.maxSupported = maxSupported;
  }

  /** Returns information about the workflow execution handling the {@code getVersion} call. */
  @Nonnull
  public WorkflowInfo getWorkflowInfo() {
    return workflowInfo;
  }

  /** Returns the change ID passed to {@code Workflow.getVersion}. */
  @Nonnull
  public String getChangeId() {
    return changeId;
  }

  /** Returns the minimum supported version passed to {@code Workflow.getVersion}. */
  public int getMinSupported() {
    return minSupported;
  }

  /** Returns the maximum supported version passed to {@code Workflow.getVersion}. */
  public int getMaxSupported() {
    return maxSupported;
  }

  @Override
  public String toString() {
    return "PreferredVersionProviderInput{"
        + "workflowInfo="
        + workflowInfo
        + ", changeId='"
        + changeId
        + '\''
        + ", minSupported="
        + minSupported
        + ", maxSupported="
        + maxSupported
        + '}';
  }
}
