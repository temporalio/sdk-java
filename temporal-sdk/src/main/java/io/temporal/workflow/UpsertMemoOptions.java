package io.temporal.workflow;

/** Options for {@link Workflow#upsertMemo(Map, UpsertMemoOptions)}. */
public final class UpsertMemoOptions {

  public static UpsertMemoOptions.Builder newBuilder() {
    return new UpsertMemoOptions.Builder();
  }

  public static UpsertMemoOptions.Builder newBuilder(UpsertMemoOptions options) {
    return new UpsertMemoOptions.Builder(options);
  }

  public static UpsertMemoOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final UpsertMemoOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = UpsertMemoOptions.newBuilder().build();
  }

  public static final class Builder {
    private Builder() {}

    private Builder(UpsertMemoOptions options) {
      if (options == null) {
        return;
      }
    }

    public UpsertMemoOptions build() {
      return new UpsertMemoOptions();
    }
  }

  private UpsertMemoOptions() {}

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public String toString() {
    return "UpsertMemoOptions{}";
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
