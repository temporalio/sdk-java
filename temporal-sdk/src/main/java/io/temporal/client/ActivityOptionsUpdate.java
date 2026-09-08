package io.temporal.client;

import io.temporal.common.Experimental;
import io.temporal.common.Priority;
import io.temporal.common.RetryOptions;
import java.time.Duration;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A single change to an activity's options, created from one of the keys on this class via {@link
 * ActivityOptionsKey#set} or {@link ActivityOptionsKey#unset}.
 *
 * <p>An option with no update in the call is left untouched.
 *
 * @param <T> type of the option's value
 */
@Experimental
public final class ActivityOptionsUpdate<T> {

  public static final ActivityOptionsKey<String> TASK_QUEUE =
      new ActivityOptionsKey<>("task_queue.name", String.class);

  public static final ActivityOptionsKey<Duration> SCHEDULE_TO_CLOSE_TIMEOUT =
      new ActivityOptionsKey<>("schedule_to_close_timeout", Duration.class);

  public static final ActivityOptionsKey<Duration> SCHEDULE_TO_START_TIMEOUT =
      new ActivityOptionsKey<>("schedule_to_start_timeout", Duration.class);

  public static final ActivityOptionsKey<Duration> START_TO_CLOSE_TIMEOUT =
      new ActivityOptionsKey<>("start_to_close_timeout", Duration.class);

  public static final ActivityOptionsKey<Duration> HEARTBEAT_TIMEOUT =
      new ActivityOptionsKey<>("heartbeat_timeout", Duration.class);

  public static final ActivityOptionsKey<Duration> START_DELAY =
      new ActivityOptionsKey<>("start_delay", Duration.class);

  public static final ActivityOptionsKey<RetryOptions> RETRY_OPTIONS =
      new ActivityOptionsKey<>("retry_policy", RetryOptions.class);

  public static final ActivityOptionsKey<Priority> PRIORITY =
      new ActivityOptionsKey<>("priority", Priority.class);

  /**
   * Typed key for one updatable activity option.
   *
   * <p>Use the keys on {@link ActivityOptionsUpdate} rather than constructing these directly.
   *
   * @param <T> type of the option's value
   */
  @Experimental
  public static final class ActivityOptionsKey<T> {

    private final String name;
    private final Class<T> valueType;

    ActivityOptionsKey(String name, Class<T> valueType) {
      this.name = name;
      this.valueType = valueType;
    }

    /** Field-mask path this key updates. */
    public String getName() {
      return name;
    }

    /** Type of this key's value. */
    public Class<T> getValueType() {
      return valueType;
    }

    /** Create an update that sets this option to the given value. */
    public ActivityOptionsUpdate<T> set(@Nonnull T value) {
      if (value == null) {
        throw new IllegalArgumentException("Value cannot be null, use unset");
      }
      return new ActivityOptionsUpdate<>(this, value);
    }

    /** Create an update that clears this option server-side. */
    public ActivityOptionsUpdate<T> unset() {
      return new ActivityOptionsUpdate<>(this, null);
    }

    @Override
    public String toString() {
      return "ActivityOptionsKey{name='" + name + "', valueType=" + valueType.getSimpleName() + '}';
    }
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
