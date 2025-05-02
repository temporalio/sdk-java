package io.temporal.client;

import com.google.common.base.Preconditions;
import io.temporal.common.Experimental;
import java.util.Objects;

/**
 * OnConflictOptions specifies the actions to be taken when using the {@link
 * io.temporal.api.enums.v1.WorkflowIdConflictPolicy#WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING}
 */
@Experimental
public class OnConflictOptions {
  public static OnConflictOptions.Builder newBuilder() {
    return new OnConflictOptions.Builder();
  }

  public static OnConflictOptions.Builder newBuilder(OnConflictOptions options) {
    return new OnConflictOptions.Builder(options);
  }

  public static OnConflictOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final OnConflictOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = OnConflictOptions.newBuilder().build();
  }

  private final boolean attachRequestId;
  private final boolean attachCompletionCallbacks;
  private final boolean attachLinks;

  private OnConflictOptions(
      boolean attachRequestId, boolean attachCompletionCallbacks, boolean attachLinks) {
    this.attachRequestId = attachRequestId;
    this.attachCompletionCallbacks = attachCompletionCallbacks;
    this.attachLinks = attachLinks;
  }

  public boolean isAttachRequestId() {
    return attachRequestId;
  }

  public boolean isAttachCompletionCallbacks() {
    return attachCompletionCallbacks;
  }

  public boolean isAttachLinks() {
    return attachLinks;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    OnConflictOptions that = (OnConflictOptions) o;
    return attachRequestId == that.attachRequestId
        && attachCompletionCallbacks == that.attachCompletionCallbacks
        && attachLinks == that.attachLinks;
  }

  @Override
  public int hashCode() {
    return Objects.hash(attachRequestId, attachCompletionCallbacks, attachLinks);
  }

  @Override
  public String toString() {
    return "OnConflictOptions{"
        + "attachRequestId="
        + attachRequestId
        + ", attachCompletionCallbacks="
        + attachCompletionCallbacks
        + ", attachLinks="
        + attachLinks
        + '}';
  }

  public static final class Builder {
    private boolean attachRequestId;
    private boolean attachCompletionCallbacks;
    private boolean attachLinks;

    public Builder(OnConflictOptions options) {
      this.attachRequestId = options.attachRequestId;
      this.attachCompletionCallbacks = options.attachCompletionCallbacks;
      this.attachLinks = options.attachLinks;
    }

    public Builder() {}

    /** Attaches the request ID to the running workflow. */
    public Builder setAttachRequestId(boolean attachRequestId) {
      this.attachRequestId = attachRequestId;
      return this;
    }

    /**
     * Attaches the completion callbacks to the running workflow. If true, AttachRequestId must be
     * true.
     */
    public Builder setAttachCompletionCallbacks(boolean attachCompletionCallbacks) {
      this.attachCompletionCallbacks = attachCompletionCallbacks;
      return this;
    }

    /** Attaches the links to the WorkflowExecutionOptionsUpdatedEvent history event. */
    public Builder setAttachLinks(boolean attachLinks) {
      this.attachLinks = attachLinks;
      return this;
    }

    public OnConflictOptions build() {
      if (attachCompletionCallbacks) {
        Preconditions.checkState(
            attachRequestId, "AttachRequestId must be true if AttachCompletionCallbacks is true");
      }
      return new OnConflictOptions(attachRequestId, attachCompletionCallbacks, attachLinks);
    }
  }
}
