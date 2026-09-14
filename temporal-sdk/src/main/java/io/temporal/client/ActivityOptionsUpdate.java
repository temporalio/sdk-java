package io.temporal.client;

import static io.temporal.internal.common.RetryOptionsUtils.toRetryPolicy;

import io.temporal.api.activity.v1.ActivityOptions;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.common.Experimental;
import io.temporal.common.Priority;
import io.temporal.common.RetryOptions;
import io.temporal.internal.common.ProtoConverters;
import io.temporal.internal.common.ProtobufTimeUtils;
import java.time.Duration;
import java.util.Optional;
import java.util.function.BiConsumer;
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
      new ActivityOptionsKey<>(
          "task_queue.name",
          String.class,
          (options, value) -> options.setTaskQueue(TaskQueue.newBuilder().setName(value).build()));

  public static final ActivityOptionsKey<Duration> SCHEDULE_TO_CLOSE_TIMEOUT =
      new ActivityOptionsKey<>(
          "schedule_to_close_timeout",
          Duration.class,
          (options, value) ->
              options.setScheduleToCloseTimeout(ProtobufTimeUtils.toProtoDuration(value)));

  public static final ActivityOptionsKey<Duration> SCHEDULE_TO_START_TIMEOUT =
      new ActivityOptionsKey<>(
          "schedule_to_start_timeout",
          Duration.class,
          (options, value) ->
              options.setScheduleToStartTimeout(ProtobufTimeUtils.toProtoDuration(value)));

  public static final ActivityOptionsKey<Duration> START_TO_CLOSE_TIMEOUT =
      new ActivityOptionsKey<>(
          "start_to_close_timeout",
          Duration.class,
          (options, value) ->
              options.setStartToCloseTimeout(ProtobufTimeUtils.toProtoDuration(value)));

  public static final ActivityOptionsKey<Duration> HEARTBEAT_TIMEOUT =
      new ActivityOptionsKey<>(
          "heartbeat_timeout",
          Duration.class,
          (options, value) ->
              options.setHeartbeatTimeout(ProtobufTimeUtils.toProtoDuration(value)));

  public static final ActivityOptionsKey<Duration> START_DELAY =
      new ActivityOptionsKey<>(
          "start_delay",
          Duration.class,
          (options, value) -> options.setStartDelay(ProtobufTimeUtils.toProtoDuration(value)));

  public static final ActivityOptionsKey<RetryOptions> RETRY_OPTIONS =
      new ActivityOptionsKey<>(
          "retry_policy",
          RetryOptions.class,
          (options, value) -> options.setRetryPolicy(toRetryPolicy(value)));

  public static final ActivityOptionsKey<Priority> PRIORITY =
      new ActivityOptionsKey<>(
          "priority",
          Priority.class,
          (options, value) -> options.setPriority(ProtoConverters.toProto(value)));

  /**
   * Typed key for one updatable activity option. Each key knows both its field-mask path and how to
   * write its value onto the request.
   *
   * <p>Use the keys on {@link ActivityOptionsUpdate} rather than constructing these directly.
   *
   * @param <T> type of the option's value
   */
  @Experimental
  public static final class ActivityOptionsKey<T> {

    private final String path;
    private final Class<T> valueType;
    private final BiConsumer<ActivityOptions.Builder, T> setter;

    ActivityOptionsKey(
        String path, Class<T> valueType, BiConsumer<ActivityOptions.Builder, T> setter) {
      this.path = path;
      this.valueType = valueType;
      this.setter = setter;
    }

    /** Field-mask path this key updates. */
    public String getPath() {
      return path;
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

    /** Writes this option's value onto the request. */
    void apply(ActivityOptions.Builder options, T value) {
      setter.accept(options, value);
    }

    @Override
    public String toString() {
      return "ActivityOptionsKey{path='" + path + "', valueType=" + valueType.getSimpleName() + '}';
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

  /**
   * Writes this update onto the request. An unset update writes nothing: it names its path in the
   * field mask but leaves the field absent, which is how the server is told to clear the option.
   */
  public void applyTo(ActivityOptions.Builder options) {
    if (value != null) {
      key.apply(options, value);
    }
  }

  @Override
  public String toString() {
    return "ActivityOptionsUpdate{key=" + key.getPath() + ", value=" + value + '}';
  }
}
