package io.temporal.workflow;

/** Options for {@link ChildWorkflowStub#signal(String, SignalChildWorkflowOptions, Object...)}. */
public final class SignalChildWorkflowOptions {

  public static SignalChildWorkflowOptions.Builder newBuilder() {
    return new SignalChildWorkflowOptions.Builder();
  }

  public static SignalChildWorkflowOptions.Builder newBuilder(SignalChildWorkflowOptions options) {
    return new SignalChildWorkflowOptions.Builder(options);
  }

  public static SignalChildWorkflowOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final SignalChildWorkflowOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = SignalChildWorkflowOptions.newBuilder().build();
  }

  public static final class Builder {
    private Builder() {}

    private Builder(SignalChildWorkflowOptions options) {
      if (options == null) {
        return;
      }
    }

    public SignalChildWorkflowOptions build() {
      return new SignalChildWorkflowOptions();
    }
  }

  private SignalChildWorkflowOptions() {}

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public String toString() {
    return "SignalChildWorkflowOptions{}";
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
