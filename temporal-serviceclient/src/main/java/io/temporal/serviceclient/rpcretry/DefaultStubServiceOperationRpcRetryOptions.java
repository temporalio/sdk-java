package io.temporal.serviceclient.rpcretry;

import io.temporal.serviceclient.RpcRetryOptions;
import java.time.Duration;

/**
 * Default rpc retry options for outgoing requests to the temporal server that supposed to be
 * processed and returned fast, like workflow start (not long polls or awaits for workflow
 * finishing).
 */
public class DefaultStubServiceOperationRpcRetryOptions {

  public static final Duration INITIAL_INTERVAL = Duration.ofMillis(100);
  public static final Duration CONGESTION_INITIAL_INTERVAL = Duration.ofMillis(1000);
  public static final Duration EXPIRATION_INTERVAL = Duration.ofMinutes(1);
  public static final int MAXIMUM_INTERVAL_MULTIPLIER = 50;
  public static final Duration MAXIMUM_INTERVAL =
      INITIAL_INTERVAL.multipliedBy(MAXIMUM_INTERVAL_MULTIPLIER);
  public static final double BACKOFF = 1.7;
  public static final double MAXIMUM_JITTER_COEFFICIENT = 0.2;

  public static final RpcRetryOptions INSTANCE;

  static {
    INSTANCE = getBuilder().validateBuildWithDefaults();
  }

  public static RpcRetryOptions.Builder getBuilder() {
    return RpcRetryOptions.newBuilder()
        .setInitialInterval(INITIAL_INTERVAL)
        .setCongestionInitialInterval(CONGESTION_INITIAL_INTERVAL)
        .setExpiration(EXPIRATION_INTERVAL)
        .setBackoffCoefficient(BACKOFF)
        .setMaximumInterval(MAXIMUM_INTERVAL)
        .setMaximumJitterCoefficient(MAXIMUM_JITTER_COEFFICIENT);
  }
}
