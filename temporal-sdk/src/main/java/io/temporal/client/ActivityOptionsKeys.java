package io.temporal.client;

import io.temporal.common.Experimental;
import io.temporal.common.Priority;
import io.temporal.common.RetryOptions;
import java.time.Duration;

/** The activity options that {@link UntypedActivityHandle#updateOptions} can change. */
@Experimental
public final class ActivityOptionsKeys {

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

  private ActivityOptionsKeys() {}
}
