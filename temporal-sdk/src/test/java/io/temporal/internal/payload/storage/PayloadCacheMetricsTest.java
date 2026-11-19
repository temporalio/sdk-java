package io.temporal.internal.payload.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.payload.storage.InMemoryPayloadCache;
import io.temporal.payload.storage.PayloadCache;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.worker.MetricsType;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.annotation.Nonnull;
import org.junit.Test;

public class PayloadCacheMetricsTest {
  private static final Map<String, String> TAGS =
      ImmutableMap.of(MetricsTag.NAMESPACE, "ns", MetricsTag.TASK_QUEUE, "tq");

  private final TestStatsReporter reporter = new TestStatsReporter();
  private final Scope rootScope =
      new RootScopeBuilder().reporter(reporter).reportEvery(com.uber.m3.util.Duration.ofMillis(10));
  private final Scope scope = rootScope.tagged(TAGS);

  @Test
  public void countsHitsAndHitBytes() throws Exception {
    Payload payload = payload("hello");
    PayloadCache cache = PayloadCacheMetrics.wrap(new InMemoryPayloadCache(), scope);
    cache.set("k", payload).join();

    assertEquals(Optional.of(payload), cache.get("k").join());
    assertEquals(Optional.of(payload), cache.get("k").join());
    assertEquals(Optional.empty(), cache.get("missing").join());
    rootScope.close();

    reporter.assertCounter(MetricsType.PAYLOAD_CACHE_LOOKUP_HIT_COUNTER, TAGS, 2);
    reporter.assertCounter(
        MetricsType.PAYLOAD_CACHE_LOOKUP_HIT_BYTES_COUNTER, TAGS, 2L * payload.getSerializedSize());
  }

  @Test
  public void countsADeclinedWrite() throws Exception {
    PayloadCache cache =
        PayloadCacheMetrics.wrap(InMemoryPayloadCache.newBuilder().setMaxEntries(0).build(), scope);

    assertFalse(cache.set("k", payload("hello")).join());
    rootScope.close();

    reporter.assertCounter(MetricsType.PAYLOAD_CACHE_REJECTION_COUNTER, withReason("declined"), 1);
    reporter.assertNoMetric(MetricsType.PAYLOAD_CACHE_REJECTION_COUNTER, withReason("error"));
  }

  @Test
  public void countsAFailedWriteWithoutHidingTheFailure() throws Exception {
    PayloadCache cache = PayloadCacheMetrics.wrap(new FailingCache(), scope);

    assertThrows(CompletionException.class, () -> cache.set("k", payload("hello")).join());
    rootScope.close();

    reporter.assertCounter(MetricsType.PAYLOAD_CACHE_REJECTION_COUNTER, withReason("error"), 1);
  }

  @Test
  public void countsNothingForAnAcceptedWrite() throws Exception {
    PayloadCache cache = PayloadCacheMetrics.wrap(new InMemoryPayloadCache(), scope);

    assertTrue(cache.set("k", payload("hello")).join());
    rootScope.close();

    reporter.assertNoMetric(MetricsType.PAYLOAD_CACHE_REJECTION_COUNTER, withReason("declined"));
    reporter.assertNoMetric(MetricsType.PAYLOAD_CACHE_REJECTION_COUNTER, withReason("error"));
  }

  @Test
  public void leavesADisabledCacheUnwrapped() {
    assertSame(PayloadCache.disabled(), PayloadCacheMetrics.wrap(PayloadCache.disabled(), scope));
  }

  private static Map<String, String> withReason(String reason) {
    return ImmutableMap.<String, String>builder().putAll(TAGS).put("reason", reason).build();
  }

  private static Payload payload(String data) {
    return Payload.newBuilder().setData(ByteString.copyFromUtf8(data)).build();
  }

  private static final class FailingCache implements PayloadCache {
    @Nonnull
    @Override
    public CompletableFuture<Optional<Payload>> get(@Nonnull String key) {
      return CompletableFuture.completedFuture(Optional.empty());
    }

    @Nonnull
    @Override
    public CompletableFuture<Boolean> set(@Nonnull String key, @Nonnull Payload payload) {
      CompletableFuture<Boolean> future = new CompletableFuture<>();
      future.completeExceptionally(new IllegalStateException("cache unavailable"));
      return future;
    }
  }
}
