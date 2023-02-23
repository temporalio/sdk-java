package io.temporal.common;

import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Representation of a search attribute update inside a workflow. Updates are usually created via
 * {@link SearchAttributeKey#valueSet} or {@link SearchAttributeKey#valueUnset}.
 */
public class SearchAttributeUpdate<T> {

  /**
   * Create an update for setting the search attribute key to a value. Most users will prefer {@link
   * SearchAttributeKey#valueSet}.
   */
  public static <T> SearchAttributeUpdate<T> valueSet(SearchAttributeKey<T> key, @Nonnull T value) {
    return new SearchAttributeUpdate<>(key, value);
  }

  /**
   * Create an update for unsetting a search attribute key. Most users will prefer {@link
   * SearchAttributeKey#valueUnset}.
   */
  public static <T> SearchAttributeUpdate<T> valueUnset(SearchAttributeKey<T> key) {
    return new SearchAttributeUpdate<>(key, null);
  }

  private final SearchAttributeKey<T> key;

  private final T value;

  private SearchAttributeUpdate(SearchAttributeKey<T> key, @Nullable T value) {
    this.key = key;
    this.value = value;
  }

  /** Get the key to set/unset. */
  public SearchAttributeKey<T> getKey() {
    return key;
  }

  /** Get the value to set, or null for unset. */
  public Optional<T> getValue() {
    return Optional.ofNullable(value);
  }
}
