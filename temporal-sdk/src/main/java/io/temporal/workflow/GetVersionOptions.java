package io.temporal.workflow;

/** Options for {@link Workflow#getVersion(String, int, int, GetVersionOptions)}. */
public final class GetVersionOptions {

  public static GetVersionOptions.Builder newBuilder() {
    return new GetVersionOptions.Builder();
  }

  public static GetVersionOptions.Builder newBuilder(GetVersionOptions options) {
    return new GetVersionOptions.Builder(options);
  }

  public static GetVersionOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final GetVersionOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = GetVersionOptions.newBuilder().build();
  }

  public static final class Builder {
    private Builder() {}

    private Builder(GetVersionOptions options) {
      if (options == null) {
        return;
      }
    }

    public GetVersionOptions build() {
      return new GetVersionOptions();
    }
  }

  private GetVersionOptions() {}

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public String toString() {
    return "GetVersionOptions{}";
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
