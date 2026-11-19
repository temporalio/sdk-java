package io.temporal.internal.payload.storage;

import com.uber.m3.tally.Counter;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.common.v1.Payload;
import io.temporal.payload.storage.PayloadCache;
import io.temporal.worker.MetricsType;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/** Wraps a {@link PayloadCache} so that its outcomes are recorded as metrics. */
public final class PayloadCacheMetrics implements PayloadCache {
  static final String REJECTION_REASON_TAG = "reason";
  static final String REJECTION_REASON_DECLINED = "declined";
  static final String REJECTION_REASON_ERROR = "error";

  /**
   * Returns {@code cache} wrapped with metrics recorded to {@code scope}, or {@code cache} itself
   * when it is {@link PayloadCache#disabled()}.
   */
  public static PayloadCache wrap(PayloadCache cache, Scope scope) {
    if (cache == PayloadCache.disabled()) {
      return cache;
    }
    return new PayloadCacheMetrics(cache, scope);
  }

  private final PayloadCache cache;
  private final Counter hit;
  private final Counter hitBytes;
  private final Counter declined;
  private final Counter error;

  private PayloadCacheMetrics(PayloadCache cache, Scope scope) {
    this.cache = cache;
    this.hit = scope.counter(MetricsType.PAYLOAD_CACHE_LOOKUP_HIT_COUNTER);
    this.hitBytes = scope.counter(MetricsType.PAYLOAD_CACHE_LOOKUP_HIT_BYTES_COUNTER);
    this.declined =
        scope
            .tagged(ImmutableMap.of(REJECTION_REASON_TAG, REJECTION_REASON_DECLINED))
            .counter(MetricsType.PAYLOAD_CACHE_REJECTION_COUNTER);
    this.error =
        scope
            .tagged(ImmutableMap.of(REJECTION_REASON_TAG, REJECTION_REASON_ERROR))
            .counter(MetricsType.PAYLOAD_CACHE_REJECTION_COUNTER);
  }

  @Nonnull
  @Override
  public CompletableFuture<Optional<Payload>> get(@Nonnull String key) {
    return cache
        .get(key)
        .thenApply(
            payload -> {
              if (payload.isPresent()) {
                hit.inc(1);
                hitBytes.inc(payload.get().getSerializedSize());
              }
              return payload;
            });
  }

  @Nonnull
  @Override
  public CompletableFuture<Boolean> set(@Nonnull String key, @Nonnull Payload payload) {
    CompletableFuture<Boolean> result;
    try {
      result = cache.set(key, payload);
    } catch (RuntimeException e) {
      error.inc(1);
      throw e;
    }
    return result.whenComplete(
        (accepted, failure) -> {
          if (failure != null) {
            error.inc(1);
          } else if (!Boolean.TRUE.equals(accepted)) {
            declined.inc(1);
          }
        });
  }
}
