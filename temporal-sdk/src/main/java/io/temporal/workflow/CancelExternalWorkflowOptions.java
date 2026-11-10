package io.temporal.workflow;

import java.util.*;
import javax.annotation.Nullable;

/** Options for {@link ExternalWorkflowStub#cancel(CancelExternalWorkflowOptions)}. */
public final class CancelExternalWorkflowOptions {

  public static CancelExternalWorkflowOptions.Builder newBuilder() {
    return new CancelExternalWorkflowOptions.Builder();
  }

  public static CancelExternalWorkflowOptions.Builder newBuilder(
      CancelExternalWorkflowOptions options) {
    return new CancelExternalWorkflowOptions.Builder(options);
  }

  public static CancelExternalWorkflowOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final CancelExternalWorkflowOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = CancelExternalWorkflowOptions.newBuilder().build();
  }

  public static final class Builder {
    private @Nullable String reason;

    private Builder() {}

    private Builder(CancelExternalWorkflowOptions options) {
      if (options == null) {
        return;
      }
      this.reason = options.reason;
    }

    /**
     * Reason recorded on the cancellation request.
     *
     * <p>Default is none.
     */
    public Builder setReason(@Nullable String reason) {
      this.reason = reason;
      return this;
    }

    public CancelExternalWorkflowOptions build() {
      return new CancelExternalWorkflowOptions(reason);
    }
  }

  private final @Nullable String reason;

  private CancelExternalWorkflowOptions(@Nullable String reason) {
    this.reason = reason;
  }

  @Nullable
  public String getReason() {
    return reason;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public String toString() {
    return "CancelExternalWorkflowOptions{" + "reason='" + reason + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    CancelExternalWorkflowOptions that = (CancelExternalWorkflowOptions) o;
    return Objects.equals(reason, that.reason);
  }

  @Override
  public int hashCode() {
    return Objects.hash(reason);
  }
}
