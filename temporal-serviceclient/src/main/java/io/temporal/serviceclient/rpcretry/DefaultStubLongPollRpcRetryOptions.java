package io.temporal.serviceclient.rpcretry;

import io.temporal.serviceclient.RpcRetryOptions;
import java.time.Duration;

/** Default rpc retry options for long polls like waiting for the workflow finishing and result. */
public class DefaultStubLongPollRpcRetryOptions {

  public static final Duration INITIAL_INTERVAL = Duration.ofMillis(200);
  public static final Duration CONGESTION_INITIAL_INTERVAL = Duration.ofMillis(1000);
  public static final Duration MAXIMUM_INTERVAL = Duration.ofSeconds(10);
  public static final double BACKOFF = 2.0;
  public static final double MAXIMUM_JITTER_COEFFICIENT = 0.2;

  // partial build because expiration is not set, long polls work with absolute deadlines instead
  public static final RpcRetryOptions INSTANCE = getBuilder().build();

  static {
    // retryer code that works with these options passes and accepts an absolute deadline
    // to ensure that the retry is finite
    INSTANCE.validate(false);
  }

  private static RpcRetryOptions.Builder getBuilder() {
    return RpcRetryOptions.newBuilder()
        .setInitialInterval(INITIAL_INTERVAL)
        .setCongestionInitialInterval(CONGESTION_INITIAL_INTERVAL)
        .setBackoffCoefficient(BACKOFF)
        .setMaximumInterval(MAXIMUM_INTERVAL)
        .setMaximumJitterCoefficient(MAXIMUM_JITTER_COEFFICIENT);
  }
}
