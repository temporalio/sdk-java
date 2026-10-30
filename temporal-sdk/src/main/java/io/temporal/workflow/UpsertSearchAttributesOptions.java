package io.temporal.workflow;

import io.temporal.common.SearchAttributeUpdate;

/**
 * Options for {@link Workflow#upsertTypedSearchAttributes(UpsertSearchAttributesOptions,
 * SearchAttributeUpdate[])}.
 */
public final class UpsertSearchAttributesOptions {

  public static UpsertSearchAttributesOptions.Builder newBuilder() {
    return new UpsertSearchAttributesOptions.Builder();
  }

  public static UpsertSearchAttributesOptions.Builder newBuilder(
      UpsertSearchAttributesOptions options) {
    return new UpsertSearchAttributesOptions.Builder(options);
  }

  public static UpsertSearchAttributesOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final UpsertSearchAttributesOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = UpsertSearchAttributesOptions.newBuilder().build();
  }

  public static final class Builder {
    private Builder() {}

    private Builder(UpsertSearchAttributesOptions options) {
      if (options == null) {
        return;
      }
    }

    public UpsertSearchAttributesOptions build() {
      return new UpsertSearchAttributesOptions();
    }
  }

  private UpsertSearchAttributesOptions() {}

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public String toString() {
    return "UpsertSearchAttributesOptions{}";
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
