package io.temporal.serviceclient;

import static org.junit.Assert.assertEquals;

import java.time.Duration;
import org.junit.Test;

public class RpcRetryOptionsTest {

  /**
   * congestionInitialInterval is the backoff used for RESOURCE_EXHAUSTED and is deliberately set
   * much higher than initialInterval. Builder.setRetryOptions must merge it from the source's
   * congestionInitialInterval, not from its initialInterval, otherwise that margin is silently
   * collapsed.
   */
  @Test
  public void setRetryOptionsMergesCongestionInitialInterval() {
    RpcRetryOptions source =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(200))
            .setCongestionInitialInterval(Duration.ofSeconds(7))
            .validateBuildWithDefaults();

    RpcRetryOptions merged =
        RpcRetryOptions.newBuilder().setRetryOptions(source).validateBuildWithDefaults();

    assertEquals(Duration.ofMillis(200), merged.getInitialInterval());
    assertEquals(Duration.ofSeconds(7), merged.getCongestionInitialInterval());
  }
}
