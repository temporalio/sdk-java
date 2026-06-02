package io.temporal.aws.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import io.temporal.common.WorkerDeploymentVersion;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Creates AWS Lambda handlers that run one Temporal worker per Lambda invocation. */
public final class LambdaWorker {
  private static final Logger log = LoggerFactory.getLogger(LambdaWorker.class);

  private static final Duration MINIMUM_AVAILABLE_RUNTIME = Duration.ofSeconds(1);
  private static final Duration LOW_AVAILABLE_RUNTIME_WARNING = Duration.ofSeconds(5);

  private LambdaWorker() {}

  /**
   * Returns an AWS Lambda Java handler that creates, starts, and shuts down one Temporal worker per
   * invocation.
   *
   * @param version worker deployment version to advertise for this worker.
   * @param configure callback invoked once while the Lambda handler is constructed.
   */
  public static RequestHandler<Object, Void> run(
      WorkerDeploymentVersion version, Consumer<LambdaWorkerOptions> configure) {
    LambdaWorkerOptions.validateVersion(version);
    Objects.requireNonNull(configure, "configure");
    try {
      LambdaWorkerOptions options = LambdaWorkerOptions.fromEnvironment(System.getenv());
      configure.accept(options);
      return newHandler(version, options);
    } catch (IOException e) {
      throw new RuntimeException("Unable to load Temporal client configuration", e);
    }
  }

  /** Returns an AWS Lambda Java handler using already-configured Lambda worker options. */
  public static RequestHandler<Object, Void> newHandler(
      WorkerDeploymentVersion version, LambdaWorkerOptions options) {
    return newHandler(version, options, new DefaultLambdaWorkerRuntime(), sleep());
  }

  static RequestHandler<Object, Void> newHandler(
      WorkerDeploymentVersion version,
      LambdaWorkerOptions options,
      LambdaWorkerRuntime runtime,
      Sleeper sleeper) {
    return new Handler(
        Objects.requireNonNull(options, "options").prepare(version),
        Objects.requireNonNull(runtime, "runtime"),
        Objects.requireNonNull(sleeper, "sleeper"));
  }

  private static Sleeper sleep() {
    return duration -> Thread.sleep(duration.toMillis());
  }

  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  private static final class Handler implements RequestHandler<Object, Void> {
    private final LambdaWorkerOptions.Prepared preparedOptions;
    private final LambdaWorkerRuntime runtime;
    private final Sleeper sleeper;

    private Handler(
        LambdaWorkerOptions.Prepared preparedOptions,
        LambdaWorkerRuntime runtime,
        Sleeper sleeper) {
      this.preparedOptions = Objects.requireNonNull(preparedOptions, "preparedOptions");
      this.runtime = runtime;
      this.sleeper = sleeper;
    }

    @Override
    public Void handleRequest(Object input, Context context) {
      Objects.requireNonNull(context, "context");

      LambdaWorkerOptions.Materialized options = preparedOptions.materialize(identityFor(context));
      validateRemainingTime(context, options.shutdownDeadlineBuffer);

      LambdaWorkerRuntime.Invocation invocation = null;
      try {
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
      } finally {
        shutdownInvocation(context, invocation, options);
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
      if (invocation != null) {
        try {
          invocation.shutdown();
          invocation.awaitTermination(options.gracefulShutdownTimeout);
          log.info(
              "Temporal Lambda worker stopped awsRequestId={} invokedFunctionArn={} taskQueue={}",
              context.getAwsRequestId(),
              context.getInvokedFunctionArn(),
              options.taskQueue);
        } catch (RuntimeException e) {
          log.error(
              "Temporal Lambda worker shutdown failed awsRequestId={} invokedFunctionArn={} taskQueue={}",
              context.getAwsRequestId(),
              context.getInvokedFunctionArn(),
              options.taskQueue,
              e);
        }
      }

      runShutdownHooks(context, options);

      if (invocation != null) {
        try {
          invocation.closeStubs(stubsCloseTimeout(options));
        } catch (RuntimeException e) {
          log.error(
              "Temporal Lambda worker service stubs close failed awsRequestId={} invokedFunctionArn={} taskQueue={}",
              context.getAwsRequestId(),
              context.getInvokedFunctionArn(),
              options.taskQueue,
              e);
        }
      }
    }

    private void runShutdownHooks(Context context, LambdaWorkerOptions.Materialized options) {
      for (Runnable hook : options.shutdownHooks) {
        try {
          hook.run();
        } catch (RuntimeException e) {
          log.error(
              "Temporal Lambda worker shutdown hook failed awsRequestId={} invokedFunctionArn={} taskQueue={}",
              context.getAwsRequestId(),
              context.getInvokedFunctionArn(),
              options.taskQueue,
              e);
        }
      }
    }

    private Duration durationUntilShutdownWindow(
        Context context, LambdaWorkerOptions.Materialized options) {
      return Duration.ofMillis(context.getRemainingTimeInMillis())
          .minus(options.shutdownDeadlineBuffer);
    }

    private Duration stubsCloseTimeout(LambdaWorkerOptions.Materialized options) {
      Duration timeout = options.shutdownDeadlineBuffer.minus(options.gracefulShutdownTimeout);
      return timeout.isNegative() ? Duration.ZERO : timeout;
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

    private static String identityFor(Context context) {
      return emptyToUnknown(context.getAwsRequestId())
          + "@"
          + emptyToUnknown(context.getInvokedFunctionArn());
    }

    private static String emptyToUnknown(String value) {
      return value == null || value.isEmpty() ? "unknown" : value;
    }
  }
}
