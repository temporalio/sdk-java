package io.temporal.workflow;

import java.util.*;

/**
 * Options for {@link ExternalWorkflowStub#signal(String, SignalExternalWorkflowOptions,
 * Object...)}.
 */
public final class SignalExternalWorkflowOptions {

  public static SignalExternalWorkflowOptions.Builder newBuilder() {
    return new SignalExternalWorkflowOptions.Builder();
  }

  public static SignalExternalWorkflowOptions.Builder newBuilder(
      SignalExternalWorkflowOptions options) {
    return new SignalExternalWorkflowOptions.Builder(options);
  }

  /**
   * Creates options for signaling an external workflow with the same settings as the provided child
   * workflow signal options.
   */
  public static SignalExternalWorkflowOptions from(SignalChildWorkflowOptions options) {
    Objects.requireNonNull(options, "options");
    return SignalExternalWorkflowOptions.newBuilder().build();
  }

  public static SignalExternalWorkflowOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final SignalExternalWorkflowOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = SignalExternalWorkflowOptions.newBuilder().build();
  }

  public static final class Builder {
    private Builder() {}

    private Builder(SignalExternalWorkflowOptions options) {
      if (options == null) {
        return;
      }
    }

    public SignalExternalWorkflowOptions build() {
      return new SignalExternalWorkflowOptions();
    }
  }

  private SignalExternalWorkflowOptions() {}

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public String toString() {
    return "SignalExternalWorkflowOptions{}";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    return true;
  }

  @Override
  public int hashCode() {
    return 0;
  }
}
