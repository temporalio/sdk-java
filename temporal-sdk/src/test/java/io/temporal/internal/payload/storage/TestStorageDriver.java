package io.temporal.internal.payload.storage;

import io.temporal.api.common.v1.Payload;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * In-memory storage driver for tests. Records what it was asked to do, and can be told to fail,
 * block or never answer so that one driver covers the cases the tests need.
 */
public final class TestStorageDriver implements StorageDriver {

  private final String name;
  private final Map<String, Payload> objects = new HashMap<>();
  private final Map<String, StorageDriverTargetInfo> targetByData = new HashMap<>();
  private int counter;

  public final List<Integer> storeBatchSizes = new CopyOnWriteArrayList<>();
  public final List<Integer> retrieveBatchSizes = new CopyOnWriteArrayList<>();
  public final List<String> retrievedKeys = new CopyOnWriteArrayList<>();
  public final List<StorageDriverTargetInfo> targets = new CopyOnWriteArrayList<>();
  public final List<String> storedData = new CopyOnWriteArrayList<>();
  public final AtomicInteger stores = new AtomicInteger();
  public final AtomicInteger retrieves = new AtomicInteger();
  public final AtomicInteger injectedFailures = new AtomicInteger();

  private volatile boolean neverAnswers;
  private final AtomicInteger storeFailures = new AtomicInteger();
  private final AtomicInteger retrieveFailures = new AtomicInteger();
  private volatile @Nullable String failStoresContaining;
  private volatile @Nullable CountDownLatch storeEntered;
  private volatile @Nullable CountDownLatch releaseStore;

  private TestStorageDriver(String name) {
    this.name = name;
  }

  public static TestStorageDriver create() {
    return new TestStorageDriver("test");
  }

  public static TestStorageDriver named(String name) {
    return new TestStorageDriver(name);
  }

  /** Neither storing nor retrieving ever finishes, so only cancellation can end the call. */
  public TestStorageDriver neverAnswers() {
    this.neverAnswers = true;
    return this;
  }

  public TestStorageDriver failStores(int times) {
    this.storeFailures.set(times);
    return this;
  }

  /** Fails the next {@code times} stores that carry a payload containing {@code marker}. */
  public TestStorageDriver failStoresContaining(String marker, int times) {
    this.failStoresContaining = marker;
    this.storeFailures.set(times);
    return this;
  }

  public TestStorageDriver failRetrieves(int times) {
    this.retrieveFailures.set(times);
    return this;
  }

  /** Holds each store until {@code release}, counting down {@code entered} on the way in. */
  public TestStorageDriver blockStores(CountDownLatch entered, CountDownLatch release) {
    this.storeEntered = entered;
    this.releaseStore = release;
    return this;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getType() {
    return "test.in-memory";
  }

  @Override
  public synchronized CompletableFuture<List<StorageDriverClaim>> store(
      StorageDriverStoreContext context, List<Payload> payloads) {
    stores.incrementAndGet();
    storeBatchSizes.add(payloads.size());
    targets.add(context.getTarget());

    CountDownLatch entered = storeEntered;
    if (entered != null) {
      entered.countDown();
    }
    CountDownLatch release = releaseStore;
    if (release != null) {
      try {
        release.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (neverAnswers) {
      return new CompletableFuture<>();
    }
    if (shouldFailStore(payloads)) {
      injectedFailures.incrementAndGet();
      return failed("storage unavailable");
    }

    List<StorageDriverClaim> claims = new ArrayList<>();
    for (Payload payload : payloads) {
      String data = payload.getData().toStringUtf8();
      storedData.add(data);
      targetByData.put(data, context.getTarget());
      String key = name + "-" + (counter++);
      objects.put(key, payload);
      claims.add(new StorageDriverClaim(Collections.singletonMap("key", key)));
    }
    return CompletableFuture.completedFuture(claims);
  }

  @Override
  public synchronized CompletableFuture<List<Payload>> retrieve(
      StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
    retrieves.incrementAndGet();
    retrieveBatchSizes.add(claims.size());
    for (StorageDriverClaim claim : claims) {
      retrievedKeys.add(claim.getClaimData().get("key"));
    }
    if (neverAnswers) {
      return new CompletableFuture<>();
    }
    if (retrieveFailures.get() > 0) {
      retrieveFailures.decrementAndGet();
      injectedFailures.incrementAndGet();
      return failed("storage unavailable");
    }
    List<Payload> payloads = new ArrayList<>();
    for (StorageDriverClaim claim : claims) {
      payloads.add(objects.get(claim.getClaimData().get("key")));
    }
    return CompletableFuture.completedFuture(payloads);
  }

  /** Forgets everything stored and recorded, and clears any injected behaviour. */
  public synchronized void reset() {
    objects.clear();
    targetByData.clear();
    counter = 0;
    storeBatchSizes.clear();
    retrieveBatchSizes.clear();
    retrievedKeys.clear();
    targets.clear();
    storedData.clear();
    stores.set(0);
    retrieves.set(0);
    injectedFailures.set(0);
    storeFailures.set(0);
    retrieveFailures.set(0);
    failStoresContaining = null;
    storeEntered = null;
    releaseStore = null;
    neverAnswers = false;
  }

  /** The target supplied when the payload with this data was stored. */
  public synchronized StorageDriverTargetInfo targetFor(String data) {
    return targetByData.get(data);
  }

  public synchronized int storedCount() {
    return objects.size();
  }

  public synchronized Collection<Payload> storedPayloads() {
    return new ArrayList<>(objects.values());
  }

  /** The target supplied with the most recent store, or {@code null} if nothing was stored. */
  public StorageDriverTargetInfo lastTarget() {
    return targets.isEmpty() ? null : targets.get(targets.size() - 1);
  }

  public boolean stored(String substring) {
    return storedData.stream().anyMatch(data -> data.contains(substring));
  }

  private boolean shouldFailStore(List<Payload> payloads) {
    if (storeFailures.get() <= 0) {
      return false;
    }
    String marker = failStoresContaining;
    if (marker == null) {
      storeFailures.decrementAndGet();
      return true;
    }
    for (Payload payload : payloads) {
      if (payload.getData().toStringUtf8().contains(marker)) {
        storeFailures.decrementAndGet();
        return true;
      }
    }
    return false;
  }

  private static <T> CompletableFuture<T> failed(String message) {
    CompletableFuture<T> future = new CompletableFuture<>();
    future.completeExceptionally(new IllegalStateException(message));
    return future;
  }
}
