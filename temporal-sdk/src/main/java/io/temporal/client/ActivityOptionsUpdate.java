package io.temporal.client;

import io.temporal.common.Experimental;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A single change to an activity's options. Updates are usually created via {@link
 * ActivityOptionsKey#valueSet} or {@link ActivityOptionsKey#valueUnset}.
 *
 * <p>An option with no update in the call is left untouched.
 *
 * @param <T> type of the option's value
 */
@Experimental
public final class ActivityOptionsUpdate<T> {

  /**
   * Create an update setting an option to a value. Most users will prefer {@link
   * ActivityOptionsKey#valueSet}.
   */
  public static <T> ActivityOptionsUpdate<T> valueSet(ActivityOptionsKey<T> key, @Nonnull T value) {
    if (value == null) {
      throw new IllegalArgumentException("Value cannot be null, use valueUnset");
    }
    return new ActivityOptionsUpdate<>(key, value);
  }

  /**
   * Create an update clearing an option. Most users will prefer {@link
   * ActivityOptionsKey#valueUnset}.
   */
  public static <T> ActivityOptionsUpdate<T> valueUnset(ActivityOptionsKey<T> key) {
    return new ActivityOptionsUpdate<>(key, null);
  }

  private final ActivityOptionsKey<T> key;
  private final @Nullable T value;

  private ActivityOptionsUpdate(ActivityOptionsKey<T> key, @Nullable T value) {
    this.key = key;
    this.value = value;
  }

  /** Get the key to set/unset. */
  public ActivityOptionsKey<T> getKey() {
    return key;
  }

  /** Get the value to set, or empty for unset. */
  public Optional<T> getValue() {
    return Optional.ofNullable(value);
  }

  @Override
  public String toString() {
    return "ActivityOptionsUpdate{key=" + key.getName() + ", value=" + value + '}';
  }
}
