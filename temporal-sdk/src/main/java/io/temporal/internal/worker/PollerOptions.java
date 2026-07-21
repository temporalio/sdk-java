package io.temporal.internal.worker;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.worker.tuning.PollerBehavior;
import io.temporal.worker.tuning.PollerBehaviorAutoscaling;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Options for component that polls Temporal task queues for tasks. */
public final class PollerOptions {

  public static final String UNHANDLED_COMMAND_EXCEPTION_MESSAGE =
      "Failed workflow task due to unhandled command. This error is likely recoverable.";

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(PollerOptions options) {
    return new Builder(options);
  }

  public static PollerOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  /**
   * If the given options are eligible for poller-autoscaling auto-enrollment (the user left this
   * poller type at its default) and the namespace advertises the auto-enroll capability, returns a
   * copy of the options with a default {@link PollerBehaviorAutoscaling} behavior. Otherwise
   * returns the options unchanged.
   *
   * <p>Must only be called before the worker's poller is created (i.e. at worker start), so the
   * resolved behavior is picked up when the poller is built.
   */
  public static PollerOptions maybeEnrollInPollerAutoscaling(
      PollerOptions options, NamespaceCapabilities namespaceCapabilities) {
    if (options.isAutoscalingAutoEnrollEligible()
        && namespaceCapabilities.isPollerAutoscalingAutoEnroll()
        && !(options.getPollerBehavior() instanceof PollerBehaviorAutoscaling)) {
      return PollerOptions.newBuilder(options)
          .setPollerBehavior(new PollerBehaviorAutoscaling())
          .build();
    }
    return options;
  }

  private static final PollerOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = PollerOptions.newBuilder().build();
  }

  public static final class Builder {

    private int maximumPollRateIntervalMilliseconds = 1000;
    private double maximumPollRatePerSecond;
    private double backoffCoefficient = 2;
    private Duration backoffInitialInterval = Duration.ofMillis(100);
    private Duration backoffCongestionInitialInterval = Duration.ofMillis(1000);
    private Duration backoffMaximumInterval = Duration.ofMinutes(1);
    private double backoffMaximumJitterCoefficient = 0.1;
    private PollerBehavior pollerBehavior;
    private String pollThreadNamePrefix;
    private Thread.UncaughtExceptionHandler uncaughtExceptionHandler;
    private boolean usingVirtualThreads;
    private ExecutorService pollerTaskExecutorOverride;
    private boolean autoscalingAutoEnrollEligible;

    private Builder() {}

    private Builder(PollerOptions options) {
      if (options == null) {
        return;
      }
      this.maximumPollRateIntervalMilliseconds = options.getMaximumPollRateIntervalMilliseconds();
      this.maximumPollRatePerSecond = options.getMaximumPollRatePerSecond();
      this.backoffCoefficient = options.getBackoffCoefficient();
      this.backoffInitialInterval = options.getBackoffInitialInterval();
      this.backoffCongestionInitialInterval = options.getBackoffCongestionInitialInterval();
      this.backoffMaximumInterval = options.getBackoffMaximumInterval();
      this.backoffMaximumJitterCoefficient = options.getBackoffMaximumJitterCoefficient();
      this.pollerBehavior = options.getPollerBehavior();
      this.pollThreadNamePrefix = options.getPollThreadNamePrefix();
      this.uncaughtExceptionHandler = options.getUncaughtExceptionHandler();
      this.usingVirtualThreads = options.isUsingVirtualThreads();
      this.pollerTaskExecutorOverride = options.getPollerTaskExecutorOverride();
      this.autoscalingAutoEnrollEligible = options.isAutoscalingAutoEnrollEligible();
    }

    /** Defines interval for measuring poll rate. Larger the interval more spiky can be the load. */
    public Builder setMaximumPollRateIntervalMilliseconds(int maximumPollRateIntervalMilliseconds) {
      this.maximumPollRateIntervalMilliseconds = maximumPollRateIntervalMilliseconds;
      return this;
    }

    /**
     * Maximum rate of polling. Measured in the interval set through {@link
     * #setMaximumPollRateIntervalMilliseconds(int)}.
     */
    public Builder setMaximumPollRatePerSecond(double maximumPollRatePerSecond) {
      this.maximumPollRatePerSecond = maximumPollRatePerSecond;
      return this;
    }

    /** Coefficient to use when calculating exponential delay in case of failures */
    public Builder setBackoffCoefficient(double backoffCoefficient) {
      this.backoffCoefficient = backoffCoefficient;
      return this;
    }

    /**
     * Initial delay in case of regular failure. If backoff coefficient is 1 then it would be the
     * constant delay between failing polls.
     */
    public Builder setBackoffInitialInterval(Duration backoffInitialInterval) {
      this.backoffInitialInterval = backoffInitialInterval;
      return this;
    }

    /**
     * Initial delay in case of congestion-related failures (i.e. RESOURCE_EXHAUSTED errors). If
     * backoff coefficient is 1 then it would be the constant delay between failing polls.
     */
    public Builder setBackoffCongestionInitialInterval(Duration backoffCongestionInitialInterval) {
      this.backoffCongestionInitialInterval = backoffCongestionInitialInterval;
      return this;
    }

    /** Maximum interval between polls in case of failures. */
    public Builder setBackoffMaximumInterval(Duration backoffMaximumInterval) {
      this.backoffMaximumInterval = backoffMaximumInterval;
      return this;
    }

    /**
     * Maximum amount of jitter to apply. 0.2 means that actual retry time can be +/- 20% of the
     * calculated time. Set to 0 to disable jitter. Must be lower than 1. Default is 0.1.
     */
    public Builder setBackoffMaximumJitterCoefficient(double backoffMaximumJitterCoefficient) {
      this.backoffMaximumJitterCoefficient = backoffMaximumJitterCoefficient;
      return this;
    }

    /** Set poller behavior. */
    public Builder setPollerBehavior(PollerBehavior pollerBehavior) {
      this.pollerBehavior = pollerBehavior;
      return this;
    }

    /** Called to report unexpected exceptions in the poller threads. */
    public Builder setUncaughtExceptionHandler(
        Thread.UncaughtExceptionHandler uncaughtExceptionHandler) {
      this.uncaughtExceptionHandler = uncaughtExceptionHandler;
      return this;
    }

    /** Prefix to use when naming poller threads. */
    public Builder setPollThreadNamePrefix(String pollThreadNamePrefix) {
      this.pollThreadNamePrefix = pollThreadNamePrefix;
      return this;
    }

    /** Use virtual threads polling threads. */
    public Builder setUsingVirtualThreads(boolean usingVirtualThreads) {
      this.usingVirtualThreads = usingVirtualThreads;
      return this;
    }

    /** Override the task executor ExecutorService */
    public Builder setPollerTaskExecutorOverride(ExecutorService overrideTaskExecutor) {
      this.pollerTaskExecutorOverride = overrideTaskExecutor;
      return this;
    }

    /**
     * Marks whether this poller type was left at its default (the user set neither a fixed poller
     * count nor a poller behavior) and is therefore eligible for poller-autoscaling auto-enrollment
     * when the namespace advertises the capability.
     */
    public Builder setAutoscalingAutoEnrollEligible(boolean autoscalingAutoEnrollEligible) {
      this.autoscalingAutoEnrollEligible = autoscalingAutoEnrollEligible;
      return this;
    }

    public PollerOptions build() {
      if (uncaughtExceptionHandler == null) {
        uncaughtExceptionHandler =
            (t, e) -> {
              if (e instanceof RuntimeException && e.getCause() instanceof StatusRuntimeException) {
                StatusRuntimeException sre = (StatusRuntimeException) e.getCause();
                if (sre.getStatus().getCode() == Status.Code.INVALID_ARGUMENT
                    && sre.getMessage().startsWith("INVALID_ARGUMENT: UnhandledCommand")) {
                  log.info(UNHANDLED_COMMAND_EXCEPTION_MESSAGE, e);
                }
              } else {
                log.error("uncaught exception", e);
              }
            };
      }

      return new PollerOptions(
          maximumPollRateIntervalMilliseconds,
          maximumPollRatePerSecond,
          backoffCoefficient,
          backoffInitialInterval,
          backoffCongestionInitialInterval,
          backoffMaximumInterval,
          backoffMaximumJitterCoefficient,
          pollerBehavior,
          uncaughtExceptionHandler,
          pollThreadNamePrefix,
          usingVirtualThreads,
          pollerTaskExecutorOverride,
          autoscalingAutoEnrollEligible);
    }
  }

  private static final Logger log = LoggerFactory.getLogger(PollerOptions.class);

  private final int maximumPollRateIntervalMilliseconds;
  private final double maximumPollRatePerSecond;
  private final double backoffCoefficient;
  private final double backoffMaximumJitterCoefficient;
  private final Duration backoffInitialInterval;
  private final Duration backoffCongestionInitialInterval;
  private final Duration backoffMaximumInterval;
  private final Thread.UncaughtExceptionHandler uncaughtExceptionHandler;
  private final String pollThreadNamePrefix;
  private final boolean usingVirtualThreads;
  private final ExecutorService pollerTaskExecutorOverride;
  private final PollerBehavior pollerBehavior;
  private final boolean autoscalingAutoEnrollEligible;

  private PollerOptions(
      int maximumPollRateIntervalMilliseconds,
      double maximumPollRatePerSecond,
      double backoffCoefficient,
      Duration backoffInitialInterval,
      Duration backoffCongestionInitialInterval,
      Duration backoffMaximumInterval,
      double backoffMaximumJitterCoefficient,
      PollerBehavior pollerBehavior,
      Thread.UncaughtExceptionHandler uncaughtExceptionHandler,
      String pollThreadNamePrefix,
      boolean usingVirtualThreads,
      ExecutorService pollerTaskExecutorOverride,
      boolean autoscalingAutoEnrollEligible) {
    this.maximumPollRateIntervalMilliseconds = maximumPollRateIntervalMilliseconds;
    this.maximumPollRatePerSecond = maximumPollRatePerSecond;
    this.backoffCoefficient = backoffCoefficient;
    this.backoffInitialInterval = backoffInitialInterval;
    this.backoffCongestionInitialInterval = backoffCongestionInitialInterval;
    this.backoffMaximumInterval = backoffMaximumInterval;
    this.backoffMaximumJitterCoefficient = backoffMaximumJitterCoefficient;
    this.pollerBehavior = pollerBehavior;
    this.uncaughtExceptionHandler = uncaughtExceptionHandler;
    this.pollThreadNamePrefix = pollThreadNamePrefix;
    this.usingVirtualThreads = usingVirtualThreads;
    this.pollerTaskExecutorOverride = pollerTaskExecutorOverride;
    this.autoscalingAutoEnrollEligible = autoscalingAutoEnrollEligible;
  }

  public int getMaximumPollRateIntervalMilliseconds() {
    return maximumPollRateIntervalMilliseconds;
  }

  public double getMaximumPollRatePerSecond() {
    return maximumPollRatePerSecond;
  }

  public double getBackoffCoefficient() {
    return backoffCoefficient;
  }

  public Duration getBackoffInitialInterval() {
    return backoffInitialInterval;
  }

  public Duration getBackoffCongestionInitialInterval() {
    return backoffCongestionInitialInterval;
  }

  public Duration getBackoffMaximumInterval() {
    return backoffMaximumInterval;
  }

  public double getBackoffMaximumJitterCoefficient() {
    return backoffMaximumJitterCoefficient;
  }

  public PollerBehavior getPollerBehavior() {
    return pollerBehavior;
  }

  public Thread.UncaughtExceptionHandler getUncaughtExceptionHandler() {
    return uncaughtExceptionHandler;
  }

  public String getPollThreadNamePrefix() {
    return pollThreadNamePrefix;
  }

  public boolean isUsingVirtualThreads() {
    return usingVirtualThreads;
  }

  public ExecutorService getPollerTaskExecutorOverride() {
    return pollerTaskExecutorOverride;
  }

  public boolean isAutoscalingAutoEnrollEligible() {
    return autoscalingAutoEnrollEligible;
  }

  @Override
  public String toString() {
    return "PollerOptions{"
        + "maximumPollRateIntervalMilliseconds="
        + maximumPollRateIntervalMilliseconds
        + ", maximumPollRatePerSecond="
        + maximumPollRatePerSecond
        + ", backoffCoefficient="
        + backoffCoefficient
        + ", backoffInitialInterval="
        + backoffInitialInterval
        + ", backoffCongestionInitialInterval="
        + backoffCongestionInitialInterval
        + ", backoffMaximumInterval="
        + backoffMaximumInterval
        + ", backoffMaximumJitterCoefficient="
        + backoffMaximumJitterCoefficient
        + ", pollerBehavior="
        + pollerBehavior
        + ", pollThreadNamePrefix='"
        + pollThreadNamePrefix
        + ", usingVirtualThreads='"
        + usingVirtualThreads
        + '\''
        + '}';
  }
}
