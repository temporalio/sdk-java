package io.temporal.aws.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.opentelemetry.TimedShutdownHook;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Creates AWS Lambda handlers that run one Temporal worker per Lambda invocation. */
public final class LambdaWorker {
  private static final Logger log = LoggerFactory.getLogger(LambdaWorker.class);

  private static final Duration MINIMUM_AVAILABLE_RUNTIME = Duration.ofSeconds(1);
  private static final Duration LOW_AVAILABLE_RUNTIME_WARNING = Duration.ofSeconds(5);

  private LambdaWorker() {}

  /**
   * Configures options for one Lambda invocation before Temporal service stubs, client, and worker
   * are created.
   *
   * <p>The supplied builder is a fresh copy of the base options for the current invocation.
   * Shutdown hooks added by this callback run for only that invocation, including when this
   * callback throws.
   */
  @FunctionalInterface
  public interface InvocationConfigurator {
    void configure(@Nonnull LambdaWorkerOptions.Builder builder, @Nonnull Context context);
  }

  /**
   * Returns an AWS Lambda Java handler that creates, starts, and shuts down one Temporal worker per
   * invocation.
   *
   * @param version worker deployment version to advertise for this worker.
   * @param configure callback invoked once while the Lambda handler is constructed.
   */
  public static RequestHandler<Object, Void> define(
      @Nonnull WorkerDeploymentVersion version,
      @Nonnull Consumer<LambdaWorkerOptions.Builder> configure) {
    LambdaWorkerOptions.validateVersion(version);
    Objects.requireNonNull(configure, "configure");
    try {
      LambdaWorkerOptions.Builder builder =
          LambdaWorkerOptions.newBuilderFromEnvironment(System.getenv());
      configure.accept(builder);
      return newHandler(version, builder.build());
    } catch (IOException e) {
      throw new RuntimeException("Unable to load Temporal client configuration", e);
    }
  }

  /**
   * Returns an AWS Lambda Java handler with both cold-start and per-invocation configuration.
   *
   * @param version worker deployment version to advertise for this worker.
   * @param configure callback invoked once while the Lambda handler is constructed.
   * @param invocationConfigure callback invoked for each Lambda invocation before Temporal service
   *     stubs, client, and worker are created. Required fields may be supplied by this callback.
   */
  public static RequestHandler<Object, Void> define(
      @Nonnull WorkerDeploymentVersion version,
      @Nonnull Consumer<LambdaWorkerOptions.Builder> configure,
      @Nonnull InvocationConfigurator invocationConfigure) {
    LambdaWorkerOptions.validateVersion(version);
    Objects.requireNonNull(configure, "configure");
    Objects.requireNonNull(invocationConfigure, "invocationConfigure");
    try {
      LambdaWorkerOptions.Builder builder =
          LambdaWorkerOptions.newBuilderFromEnvironment(System.getenv());
      configure.accept(builder);
      return newHandler(version, builder.build(), invocationConfigure);
    } catch (IOException e) {
      throw new RuntimeException("Unable to load Temporal client configuration", e);
    }
  }

  /** Returns an AWS Lambda Java handler using already-configured Lambda worker options. */
  public static RequestHandler<Object, Void> newHandler(
      @Nonnull WorkerDeploymentVersion version, @Nonnull LambdaWorkerOptions options) {
    return newHandler(
        version, options, new DefaultLambdaWorkerRuntime(), sleep(), systemMonotonicClock());
  }

  /**
   * Returns an AWS Lambda Java handler using base options and per-invocation configuration.
   *
   * <p>The supplied options are copied for each invocation before {@code invocationConfigure} runs.
   * Required fields may be supplied by {@code invocationConfigure}.
   */
  public static RequestHandler<Object, Void> newHandler(
      @Nonnull WorkerDeploymentVersion version,
      @Nonnull LambdaWorkerOptions options,
      @Nonnull InvocationConfigurator invocationConfigure) {
    return newHandler(
        version,
        options,
        invocationConfigure,
        new DefaultLambdaWorkerRuntime(),
        sleep(),
        systemMonotonicClock());
  }

  static RequestHandler<Object, Void> newHandler(
      WorkerDeploymentVersion version,
      LambdaWorkerOptions options,
      LambdaWorkerRuntime runtime,
      Sleeper sleeper) {
    // This overload exists to let tests inject a fake runtime and sleeper.
    return newHandler(version, options, runtime, sleeper, systemMonotonicClock());
  }

  static RequestHandler<Object, Void> newHandler(
      WorkerDeploymentVersion version,
      LambdaWorkerOptions options,
      InvocationConfigurator invocationConfigure,
      LambdaWorkerRuntime runtime,
      Sleeper sleeper) {
    // This overload exists to let tests inject a fake runtime and sleeper.
    return newHandler(
        version, options, invocationConfigure, runtime, sleeper, systemMonotonicClock());
  }

  static RequestHandler<Object, Void> newHandler(
      WorkerDeploymentVersion version,
      LambdaWorkerOptions options,
      LambdaWorkerRuntime runtime,
      Sleeper sleeper,
      MonotonicClock clock) {
    // This overload exists to let tests inject a deterministic clock.
    return new Handler(
        Objects.requireNonNull(options, "options").prepare(version),
        Objects.requireNonNull(runtime, "runtime"),
        Objects.requireNonNull(sleeper, "sleeper"),
        Objects.requireNonNull(clock, "clock"),
        false);
  }

  static RequestHandler<Object, Void> newHandler(
      WorkerDeploymentVersion version,
      LambdaWorkerOptions options,
      InvocationConfigurator invocationConfigure,
      LambdaWorkerRuntime runtime,
      Sleeper sleeper,
      MonotonicClock clock) {
    LambdaWorkerOptions.validateVersion(version);
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(invocationConfigure, "invocationConfigure");
    return new Handler(
        context -> {
          LambdaWorkerOptions.Builder builder = options.toBuilder();
          try {
            invocationConfigure.configure(builder, context);
            return builder.build().prepare(version).materialize(identityFor(context));
          } catch (RuntimeException e) {
            throw new InvocationConfigurationException(e, builder);
          }
        },
        Objects.requireNonNull(runtime, "runtime"),
        Objects.requireNonNull(sleeper, "sleeper"),
        Objects.requireNonNull(clock, "clock"),
        true);
  }

  private static Sleeper sleep() {
    return duration -> Thread.sleep(duration.toMillis());
  }

  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  interface MonotonicClock {
    long nanoTime();
  }

  private static MonotonicClock systemMonotonicClock() {
    return System::nanoTime;
  }

  private static String identityFor(Context context) {
    return emptyToUnknown(context.getAwsRequestId())
        + "@"
        + emptyToUnknown(context.getInvokedFunctionArn());
  }

  private static String emptyToUnknown(String value) {
    return value == null || value.isEmpty() ? "unknown" : value;
  }

  private interface OptionsMaterializer {
    LambdaWorkerOptions.Materialized materialize(Context context);
  }

  private static final class Handler implements RequestHandler<Object, Void> {
    private final OptionsMaterializer optionsMaterializer;
    private final LambdaWorkerRuntime runtime;
    private final Sleeper sleeper;
    private final MonotonicClock clock;
    private final boolean runShutdownHooksBeforeRuntimeCreation;

    private Handler(
        LambdaWorkerOptions.Materialized preparedOptions,
        LambdaWorkerRuntime runtime,
        Sleeper sleeper,
        MonotonicClock clock,
        boolean runShutdownHooksBeforeRuntimeCreation) {
      this(
          context -> preparedOptions.materialize(identityFor(context)),
          runtime,
          sleeper,
          clock,
          runShutdownHooksBeforeRuntimeCreation);
      Objects.requireNonNull(preparedOptions, "preparedOptions");
    }

    private Handler(
        OptionsMaterializer optionsMaterializer,
        LambdaWorkerRuntime runtime,
        Sleeper sleeper,
        MonotonicClock clock) {
      this(optionsMaterializer, runtime, sleeper, clock, false);
    }

    private Handler(
        OptionsMaterializer optionsMaterializer,
        LambdaWorkerRuntime runtime,
        Sleeper sleeper,
        MonotonicClock clock,
        boolean runShutdownHooksBeforeRuntimeCreation) {
      this.optionsMaterializer = Objects.requireNonNull(optionsMaterializer, "optionsMaterializer");
      this.runtime = runtime;
      this.sleeper = sleeper;
      this.clock = clock;
      this.runShutdownHooksBeforeRuntimeCreation = runShutdownHooksBeforeRuntimeCreation;
    }

    @Override
    public Void handleRequest(Object input, Context context) {
      Objects.requireNonNull(context, "context");

      LambdaWorkerOptions.Materialized options = null;
      LambdaWorkerRuntime.Invocation invocation = null;
      try {
        options = optionsMaterializer.materialize(context);
        validateRemainingTime(context, options.shutdownDeadlineBuffer);

        invocation =
            runtime.create(
                options.serviceStubsOptions,
                options.clientOptions,
                options.workerFactoryOptions,
                options.taskQueue,
                options.workerOptions);

        for (LambdaWorkerOptions.Registration registration : options.registrations) {
          registration.apply(invocation.getWorkerRegistrar());
        }

        invocation.start();
        log.info(
            "Temporal Lambda worker started awsRequestId={} invokedFunctionArn={} taskQueue={} identity={}",
            context.getAwsRequestId(),
            context.getInvokedFunctionArn(),
            options.taskQueue,
            options.workerOptions.getIdentity());

        sleepUntilShutdownWindow(context, options);
        return null;
      } catch (InvocationConfigurationException e) {
        runShutdownHooks(
            context,
            e.taskQueue,
            e.shutdownHooks,
            cleanupDeadlineNanos(context, e.gracefulShutdownTimeout, e.shutdownDeadlineBuffer));
        throw e.failure;
      } catch (RuntimeException e) {
        if (invocation == null && options != null && runShutdownHooksBeforeRuntimeCreation) {
          runShutdownHooks(
              context,
              options.taskQueue,
              options.shutdownHooks,
              cleanupDeadlineNanos(context, options));
        }
        throw e;
      } finally {
        if (invocation != null) {
          shutdownInvocation(context, invocation, options);
        }
      }
    }

    private void sleepUntilShutdownWindow(
        Context context, LambdaWorkerOptions.Materialized options) {
      Duration runDuration = durationUntilShutdownWindow(context, options);
      if (runDuration.isZero() || runDuration.isNegative()) {
        return;
      }

      try {
        sleeper.sleep(runDuration);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted while running Temporal Lambda worker", e);
      }
    }

    private void shutdownInvocation(
        Context context,
        LambdaWorkerRuntime.Invocation invocation,
        LambdaWorkerOptions.Materialized options) {
      Long cleanupDeadlineNanos = null;
      if (invocation != null) {
        try {
          invocation.shutdown();
          invocation.awaitTermination(options.gracefulShutdownTimeout);
        } catch (RuntimeException e) {
          log.error(
              "Temporal Lambda worker shutdown failed awsRequestId={} invokedFunctionArn={} taskQueue={}",
              context.getAwsRequestId(),
              context.getInvokedFunctionArn(),
              options.taskQueue,
              e);
        }

        cleanupDeadlineNanos = cleanupDeadlineNanos(context, options);
        boolean terminated = isTerminated(context, invocation, options);
        if (!terminated) {
          terminated = forceShutdownInvocation(context, invocation, options, cleanupDeadlineNanos);
        }
        if (terminated) {
          log.info(
              "Temporal Lambda worker stopped awsRequestId={} invokedFunctionArn={} taskQueue={}",
              context.getAwsRequestId(),
              context.getInvokedFunctionArn(),
              options.taskQueue);
        }
      }

      if (cleanupDeadlineNanos == null) {
        cleanupDeadlineNanos = cleanupDeadlineNanos(context, options);
      }
      runShutdownHooks(context, options, cleanupDeadlineNanos.longValue());

      try {
        invocation.closeStubs(remainingCleanupTime(cleanupDeadlineNanos.longValue()));
      } catch (RuntimeException e) {
        log.error(
            "Temporal Lambda worker service stubs close failed awsRequestId={} invokedFunctionArn={} taskQueue={}",
            context.getAwsRequestId(),
            context.getInvokedFunctionArn(),
            options.taskQueue,
            e);
      }
    }

    private boolean forceShutdownInvocation(
        Context context,
        LambdaWorkerRuntime.Invocation invocation,
        LambdaWorkerOptions.Materialized options,
        long cleanupDeadlineNanos) {
      log.warn(
          "Temporal Lambda worker did not stop before graceful shutdown timeout; forcing stop awsRequestId={} invokedFunctionArn={} taskQueue={}",
          context.getAwsRequestId(),
          context.getInvokedFunctionArn(),
          options.taskQueue);
      try {
        invocation.shutdownNow();
      } catch (RuntimeException e) {
        log.error(
            "Temporal Lambda worker forced shutdown failed awsRequestId={} invokedFunctionArn={} taskQueue={}",
            context.getAwsRequestId(),
            context.getInvokedFunctionArn(),
            options.taskQueue,
            e);
      }

      try {
        invocation.awaitTermination(remainingCleanupTime(cleanupDeadlineNanos));
      } catch (RuntimeException e) {
        log.error(
            "Temporal Lambda worker forced shutdown wait failed awsRequestId={} invokedFunctionArn={} taskQueue={}",
            context.getAwsRequestId(),
            context.getInvokedFunctionArn(),
            options.taskQueue,
            e);
      }

      boolean terminated = isTerminated(context, invocation, options);
      if (!terminated) {
        log.warn(
            "Temporal Lambda worker did not terminate after forced shutdown awsRequestId={} invokedFunctionArn={} taskQueue={}",
            context.getAwsRequestId(),
            context.getInvokedFunctionArn(),
            options.taskQueue);
      }
      return terminated;
    }

    private boolean isTerminated(
        Context context,
        LambdaWorkerRuntime.Invocation invocation,
        LambdaWorkerOptions.Materialized options) {
      try {
        return invocation.isTerminated();
      } catch (RuntimeException e) {
        log.error(
            "Temporal Lambda worker termination check failed awsRequestId={} invokedFunctionArn={} taskQueue={}",
            context.getAwsRequestId(),
            context.getInvokedFunctionArn(),
            options.taskQueue,
            e);
        return false;
      }
    }

    private void runShutdownHooks(
        Context context, LambdaWorkerOptions.Materialized options, long cleanupDeadlineNanos) {
      runShutdownHooks(context, options.taskQueue, options.shutdownHooks, cleanupDeadlineNanos);
    }

    private void runShutdownHooks(
        Context context,
        String taskQueue,
        List<Runnable> shutdownHooks,
        long cleanupDeadlineNanos) {
      for (Runnable hook : shutdownHooks) {
        try {
          if (hook instanceof TimedShutdownHook) {
            ((TimedShutdownHook) hook).run(remainingCleanupTime(cleanupDeadlineNanos));
          } else {
            hook.run();
          }
        } catch (RuntimeException e) {
          log.error(
              "Temporal Lambda worker shutdown hook failed awsRequestId={} invokedFunctionArn={} taskQueue={}",
              context.getAwsRequestId(),
              context.getInvokedFunctionArn(),
              taskQueue,
              e);
        }
      }
    }

    private Duration durationUntilShutdownWindow(
        Context context, LambdaWorkerOptions.Materialized options) {
      return Duration.ofMillis(context.getRemainingTimeInMillis())
          .minus(options.shutdownDeadlineBuffer);
    }

    private void validateRemainingTime(Context context, Duration shutdownDeadlineBuffer) {
      Duration available =
          Duration.ofMillis(context.getRemainingTimeInMillis()).minus(shutdownDeadlineBuffer);
      if (available.compareTo(MINIMUM_AVAILABLE_RUNTIME) <= 0) {
        throw new IllegalStateException(
            "Insufficient Lambda invocation time remaining after shutdown buffer: "
                + available.toMillis()
                + "ms");
      }
      if (available.compareTo(LOW_AVAILABLE_RUNTIME_WARNING) < 0) {
        log.warn(
            "Temporal Lambda worker has low remaining time awsRequestId={} invokedFunctionArn={} availableRuntimeMs={} shutdownDeadlineBufferMs={}",
            context.getAwsRequestId(),
            context.getInvokedFunctionArn(),
            available.toMillis(),
            shutdownDeadlineBuffer.toMillis());
      }
    }

    private long cleanupDeadlineNanos(Context context, LambdaWorkerOptions.Materialized options) {
      return cleanupDeadlineNanos(
          context, options.gracefulShutdownTimeout, options.shutdownDeadlineBuffer);
    }

    private long cleanupDeadlineNanos(
        Context context, Duration gracefulShutdownTimeout, Duration shutdownDeadlineBuffer) {
      return clock.nanoTime()
          + cleanupWindow(context, gracefulShutdownTimeout, shutdownDeadlineBuffer).toNanos();
    }

    private Duration cleanupWindow(Context context, LambdaWorkerOptions.Materialized options) {
      return cleanupWindow(
          context, options.gracefulShutdownTimeout, options.shutdownDeadlineBuffer);
    }

    private Duration cleanupWindow(
        Context context, Duration gracefulShutdownTimeout, Duration shutdownDeadlineBuffer) {
      Duration configuredWindow =
          nonNegative(shutdownDeadlineBuffer.minus(gracefulShutdownTimeout));
      Duration remaining = remainingInvocationTime(context);
      return configuredWindow.compareTo(remaining) <= 0 ? configuredWindow : remaining;
    }

    private Duration remainingCleanupTime(long cleanupDeadlineNanos) {
      return nonNegative(Duration.ofNanos(cleanupDeadlineNanos - clock.nanoTime()));
    }

    private Duration remainingInvocationTime(Context context) {
      return nonNegative(Duration.ofMillis(context.getRemainingTimeInMillis()));
    }

    private static Duration nonNegative(Duration duration) {
      return duration.isNegative() ? Duration.ZERO : duration;
    }
  }

  private static final class InvocationConfigurationException extends RuntimeException {
    private final RuntimeException failure;
    private final String taskQueue;
    private final Duration gracefulShutdownTimeout;
    private final Duration shutdownDeadlineBuffer;
    private final List<Runnable> shutdownHooks;

    private InvocationConfigurationException(
        RuntimeException failure, LambdaWorkerOptions.Builder builder) {
      super(failure);
      this.failure = failure;
      this.taskQueue = builder.getTaskQueue();
      this.gracefulShutdownTimeout = builder.getGracefulShutdownTimeout();
      this.shutdownDeadlineBuffer = builder.getShutdownDeadlineBuffer();
      this.shutdownHooks = builder.getShutdownHooks();
    }
  }
}
