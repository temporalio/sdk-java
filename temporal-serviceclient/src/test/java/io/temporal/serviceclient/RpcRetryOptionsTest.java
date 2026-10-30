package io.temporal.serviceclient;

import static org.junit.Assert.assertEquals;

import io.grpc.Status;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

  @Test
  public void aBuilderCopiedFromOptionsCanAddDoNotRetryItems() {
    RpcRetryOptions source =
        RpcRetryOptions.newBuilder()
            .addDoNotRetry(Status.Code.NOT_FOUND, null)
            .validateBuildWithDefaults();

    RpcRetryOptions copy =
        RpcRetryOptions.newBuilder(source)
            .addDoNotRetry(Status.Code.ABORTED, null)
            .validateBuildWithDefaults();

    assertEquals(Arrays.asList(Status.Code.NOT_FOUND, Status.Code.ABORTED), codes(copy));
    assertEquals(Collections.singletonList(Status.Code.NOT_FOUND), codes(source));
  }

  @Test
  public void reusingABuilderDoesNotChangeOptionsItAlreadyBuilt() {
    RpcRetryOptions.Builder builder =
        RpcRetryOptions.newBuilder().addDoNotRetry(Status.Code.NOT_FOUND, null);
    RpcRetryOptions validated = builder.validateBuildWithDefaults();
    RpcRetryOptions built = builder.build();

    builder.addDoNotRetry(Status.Code.ABORTED, null);

    assertEquals(Collections.singletonList(Status.Code.NOT_FOUND), codes(validated));
    assertEquals(Collections.singletonList(Status.Code.NOT_FOUND), codes(built));
    assertEquals(Arrays.asList(Status.Code.NOT_FOUND, Status.Code.ABORTED), codes(builder.build()));
  }

  private static List<Status.Code> codes(RpcRetryOptions options) {
    List<Status.Code> codes = new ArrayList<>();
    for (RpcRetryOptions.DoNotRetryItem item : options.getDoNotRetry()) {
      codes.add(item.getCode());
    }
    return codes;
  }
}
