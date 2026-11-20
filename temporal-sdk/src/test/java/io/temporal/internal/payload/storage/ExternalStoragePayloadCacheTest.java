package io.temporal.internal.payload.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.CancellationToken;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.InMemoryPayloadCache;
import io.temporal.payload.storage.PayloadCache;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import org.junit.Test;

/** Tests how external storage uses a payload cache when storing and retrieving. */
public class ExternalStoragePayloadCacheTest {

  @Test
  public void storeFillsTheCacheSoRetrieveSkipsTheDriver() throws Exception {
    TestStorageDriver driver = TestStorageDriver.named("d1");
    ExternalStoragePayloadTransformer transformer = transformer(driver, new InMemoryPayloadCache());
    List<Payload> input = Arrays.asList(payload("a"), payload("b"));

    List<Payload> stored = store(transformer, input);
    List<Payload> retrieved = retrieve(transformer, stored);

    assertEquals(input, retrieved);
    assertEquals(0, driver.retrieves.get());
  }

  @Test
  public void aMissDownloadsAndFillsTheCache() throws Exception {
    TestStorageDriver driver = TestStorageDriver.named("d1");
    List<Payload> input = Collections.singletonList(payload("a"));
    List<Payload> stored = store(transformer(driver, PayloadCache.disabled()), input);
    ExternalStoragePayloadTransformer cached = transformer(driver, new InMemoryPayloadCache());

    assertEquals(input, retrieve(cached, stored));
    assertEquals(input, retrieve(cached, stored));

    assertEquals(1, driver.retrieves.get());
  }

  @Test
  public void onlyMissesAreDownloaded() throws Exception {
    TestStorageDriver driver = TestStorageDriver.named("d1");
    PayloadCache cache = new InMemoryPayloadCache();
    List<Payload> cachedStored = store(transformer(driver, cache), singletonPayload("hit"));
    List<Payload> uncachedStored =
        store(transformer(driver, PayloadCache.disabled()), singletonPayload("miss"));
    List<Payload> references = Arrays.asList(cachedStored.get(0), uncachedStored.get(0));

    List<Payload> retrieved = retrieve(transformer(driver, cache), references);

    assertEquals(Arrays.asList(payload("hit"), payload("miss")), retrieved);
    assertEquals(Collections.singletonList(1), driver.retrieveBatchSizes);
    assertEquals(Collections.singletonList("d1-1"), driver.retrievedKeys);
  }

  @Test
  public void aDisabledCacheIsNeverCalled() throws Exception {
    TestStorageDriver driver = TestStorageDriver.named("d1");
    ExternalStoragePayloadTransformer transformer = transformer(driver, PayloadCache.disabled());

    List<Payload> stored = store(transformer, singletonPayload("a"));
    retrieve(transformer, stored);
    retrieve(transformer, stored);

    assertEquals(2, driver.retrieves.get());
  }

  @Test
  public void aCacheThatThrowsNeverFailsTheOperation() throws Exception {
    assertCacheFailuresAreIgnored(new ThrowingCache());
  }

  @Test
  public void aCacheThatCompletesExceptionallyNeverFailsTheOperation() throws Exception {
    assertCacheFailuresAreIgnored(new ExceptionallyCompletingCache());
  }

  @Test
  public void aCacheThatReturnsNullFuturesNeverFailsTheOperation() throws Exception {
    assertCacheFailuresAreIgnored(new NullReturningCache());
  }

  @Test
  public void storeWaitsForTheCacheWriteToComplete() {
    TestStorageDriver driver = TestStorageDriver.named("d1");
    PendingWriteCache cache = new PendingWriteCache();

    CompletableFuture<List<Payload>> store =
        transformer(driver, cache).store(singletonPayload("a"), null, CancellationToken.none());

    assertEquals(1, cache.writes.get());
    assertFalse(store.isDone());
    cache.pendingWrite.complete(true);
    assertTrue(store.isDone());
  }

  @Test
  public void retrieveWaitsForTheCacheWriteToComplete() throws Exception {
    TestStorageDriver driver = TestStorageDriver.named("d1");
    List<Payload> stored =
        store(transformer(driver, PayloadCache.disabled()), singletonPayload("a"));
    PendingWriteCache cache = new PendingWriteCache();

    CompletableFuture<List<Payload>> retrieve =
        transformer(driver, cache).retrieve(stored, CancellationToken.none());

    assertEquals(1, cache.writes.get());
    assertFalse(retrieve.isDone());
    cache.pendingWrite.complete(true);
    assertEquals(singletonPayload("a"), retrieve.get());
  }

  @Test
  public void anUnregisteredDriverFailsEvenWhenThePayloadIsCached() throws Exception {
    PayloadCache cache = new InMemoryPayloadCache();
    List<Payload> stored =
        store(transformer(TestStorageDriver.named("d1"), cache), singletonPayload("a"));

    CompletableFuture<List<Payload>> retrieve =
        transformer(TestStorageDriver.named("other"), cache)
            .retrieve(stored, CancellationToken.none());

    ExecutionException error = assertThrows(ExecutionException.class, retrieve::get);
    assertTrue(error.getCause() instanceof IllegalStateException);
  }

  private static void assertCacheFailuresAreIgnored(PayloadCache cache) throws Exception {
    TestStorageDriver driver = TestStorageDriver.named("d1");
    ExternalStoragePayloadTransformer transformer = transformer(driver, cache);
    List<Payload> input = singletonPayload("a");

    List<Payload> stored = store(transformer, input);

    assertEquals(input, retrieve(transformer, stored));
    assertEquals(1, driver.retrieves.get());
  }

  private static ExternalStoragePayloadTransformer transformer(
      TestStorageDriver driver, PayloadCache cache) {
    return ExternalStoragePayloadTransformer.fromOptions(
        ExternalStorage.newBuilder().setDriver(driver).setPayloadSizeThreshold(0).build(),
        "default",
        cache);
  }

  private static List<Payload> store(
      ExternalStoragePayloadTransformer transformer, List<Payload> payloads) throws Exception {
    return transformer.store(payloads, null, CancellationToken.none()).get();
  }

  private static List<Payload> retrieve(
      ExternalStoragePayloadTransformer transformer, List<Payload> payloads) throws Exception {
    return transformer.retrieve(payloads, CancellationToken.none()).get();
  }

  private static List<Payload> singletonPayload(String data) {
    return Collections.singletonList(payload(data));
  }

  private static Payload payload(String data) {
    return Payload.newBuilder().setData(ByteString.copyFromUtf8(data)).build();
  }

  private static final class ThrowingCache implements PayloadCache {
    @Nonnull
    @Override
    public CompletableFuture<Optional<Payload>> get(@Nonnull String key) {
      throw new IllegalStateException("cache unavailable");
    }

    @Nonnull
    @Override
    public CompletableFuture<Boolean> set(@Nonnull String key, @Nonnull Payload payload) {
      throw new IllegalStateException("cache unavailable");
    }
  }

  private static final class ExceptionallyCompletingCache implements PayloadCache {
    @Nonnull
    @Override
    public CompletableFuture<Optional<Payload>> get(@Nonnull String key) {
      return failed();
    }

    @Nonnull
    @Override
    public CompletableFuture<Boolean> set(@Nonnull String key, @Nonnull Payload payload) {
      return failed();
    }

    private static <T> CompletableFuture<T> failed() {
      CompletableFuture<T> future = new CompletableFuture<>();
      future.completeExceptionally(new IllegalStateException("cache unavailable"));
      return future;
    }
  }

  private static final class NullReturningCache implements PayloadCache {
    @Nonnull
    @Override
    public CompletableFuture<Optional<Payload>> get(@Nonnull String key) {
      return null;
    }

    @Nonnull
    @Override
    public CompletableFuture<Boolean> set(@Nonnull String key, @Nonnull Payload payload) {
      return null;
    }
  }

  /** Misses on every lookup and holds every write until {@link #pendingWrite} completes. */
  private static final class PendingWriteCache implements PayloadCache {
    final CompletableFuture<Boolean> pendingWrite = new CompletableFuture<>();
    final AtomicInteger writes = new AtomicInteger();

    @Nonnull
    @Override
    public CompletableFuture<Optional<Payload>> get(@Nonnull String key) {
      return CompletableFuture.completedFuture(Optional.empty());
    }

    @Nonnull
    @Override
    public CompletableFuture<Boolean> set(@Nonnull String key, @Nonnull Payload payload) {
      writes.incrementAndGet();
      return pendingWrite;
    }
  }
}
