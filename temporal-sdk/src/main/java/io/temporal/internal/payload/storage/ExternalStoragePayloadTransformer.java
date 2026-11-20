package io.temporal.internal.payload.storage;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.CancellationToken;
import io.temporal.internal.common.ListUtils;
import io.temporal.internal.concurrent.structured.TaskScope;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.PayloadCache;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverSelectContext;
import io.temporal.payload.storage.StorageDriverSelector;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Transforms one payload list between inline payloads and external-storage references by routing
 * entries to storage drivers.
 */
final class ExternalStoragePayloadTransformer {
  private final Map<String, StorageDriver> driversByName;
  private final StorageDriverSelector selector;
  private final int payloadSizeThreshold;
  private final String namespace;
  private final PayloadCache payloadCache;

  /**
   * @param payloadCache cache for stored and retrieved payloads, or {@link PayloadCache#disabled()}
   *     to turn caching off.
   */
  static ExternalStoragePayloadTransformer fromOptions(
      ExternalStorage options, String namespace, PayloadCache payloadCache) {
    Map<String, StorageDriver> driversByName = new LinkedHashMap<>();
    for (StorageDriver driver : options.getDrivers()) {
      driversByName.put(driver.getName(), driver);
    }
    return new ExternalStoragePayloadTransformer(
        driversByName,
        options.getDriverSelector(),
        options.getPayloadSizeThreshold(),
        namespace,
        payloadCache);
  }

  private ExternalStoragePayloadTransformer(
      Map<String, StorageDriver> driversByName,
      StorageDriverSelector selector,
      int payloadSizeThreshold,
      String namespace,
      PayloadCache payloadCache) {
    this.driversByName = driversByName;
    this.selector = selector;
    this.payloadSizeThreshold = payloadSizeThreshold;
    this.namespace = Objects.requireNonNull(namespace, "namespace");
    this.payloadCache = Objects.requireNonNull(payloadCache, "payloadCache");
  }

  private boolean isCachingEnabled() {
    return payloadCache != PayloadCache.disabled();
  }

  CompletableFuture<List<Payload>> store(
      List<Payload> payloads,
      @Nullable StorageDriverTargetInfo target,
      CancellationToken<CancellationException> cancellationToken) {
    return store(payloads, target, cancellationToken, null);
  }

  CompletableFuture<List<Payload>> store(
      List<Payload> payloads,
      @Nullable StorageDriverTargetInfo target,
      CancellationToken<CancellationException> cancellationToken,
      @Nullable StorageOperationMetrics metrics) {
    StorageDriverSelectContext selectContext =
        new StorageDriverSelectContextImpl(target, namespace, cancellationToken);
    Map<String, Batch<Payload>> batches;
    try {
      batches = buildStoreBatches(payloads, selectContext);
    } catch (RuntimeException e) {
      return failedFuture(e);
    }
    if (batches.isEmpty()) {
      return CompletableFuture.completedFuture(payloads);
    }
    String[] cacheKeys = new String[payloads.size()];
    return runStoreDrivers(batches, target, cancellationToken, metrics, cacheKeys)
        .thenCompose(
            referencePayloads ->
                writeToCache(cacheKeys, payloads)
                    .thenApply(ignored -> applyPayloadReplacements(payloads, referencePayloads)));
  }

  private Map<String, Batch<Payload>> buildStoreBatches(
      List<Payload> payloads, StorageDriverSelectContext context) {
    Map<String, Batch<Payload>> batches = new LinkedHashMap<>();
    for (int i = 0; i < payloads.size(); i++) {
      Payload payload = payloads.get(i);
      if (payloadSizeThreshold > 0 && payload.getSerializedSize() < payloadSizeThreshold) {
        continue;
      }
      StorageDriver driver = selector.selectDriver(context, payload);
      if (driver == null) {
        continue;
      }
      if (driversByName.get(driver.getName()) != driver) {
        throw new IllegalStateException(
            "Storage driver selector returned a driver not registered with this external storage: '"
                + driver.getName()
                + "'");
      }
      batches.computeIfAbsent(driver.getName(), name -> new Batch<>(driver)).add(i, payload);
    }
    return batches;
  }

  private CompletableFuture<List<IndexedValue<Payload>>> runStoreDrivers(
      Map<String, Batch<Payload>> batches,
      @Nullable StorageDriverTargetInfo target,
      CancellationToken<CancellationException> cancellationToken,
      @Nullable StorageOperationMetrics metrics,
      String[] cacheKeys) {
    return withDriverScope(
        cancellationToken,
        scope -> {
          StorageDriverStoreContext context =
              new StorageDriverStoreContextImpl(target, namespace, scope.token());
          for (Batch<Payload> batch : batches.values()) {
            long startNanos = System.nanoTime();
            CompletableFuture<List<StorageDriverClaim>> call =
                batch.driver.store(context, batch.values());
            // Observes the call rather than wrapping it: the scope must keep the driver's own
            // future so cancellation still reaches the driver.
            call.whenComplete(
                (claims, error) ->
                    recordBatch(
                        metrics,
                        batch.driver,
                        startNanos,
                        error == null ? batch.size() : 0,
                        error == null ? serializedSizeOf(batch.values()) : 0));
            scope.attach(call).map(claims -> createReferencePayloads(batch, claims, cacheKeys));
          }
          return scope.awaitAll(ListUtils::flatten);
        });
  }

  /**
   * Runs {@code body} in a scope that is also cancelled by {@code cancellationToken}, so a caller
   * abandoning the operation trips the token the drivers observe.
   */
  private static CompletableFuture<List<IndexedValue<Payload>>> withDriverScope(
      CancellationToken<CancellationException> cancellationToken,
      Function<
              TaskScope<List<IndexedValue<Payload>>>,
              CompletableFuture<List<IndexedValue<Payload>>>>
          body) {
    return TaskScope.withScope(
        (TaskScope<List<IndexedValue<Payload>>> scope) -> {
          CancellationToken.Registration registration =
              cancellationToken.onCancel(scope::cancelAll);
          CompletableFuture<List<IndexedValue<Payload>>> result;
          try {
            result = body.apply(scope);
          } catch (Throwable t) {
            scope.cancelAll();
            result = failedFuture(t);
          }
          // The registration outlives body(), so it is released only once the work settles.
          return result.whenComplete((ignored, error) -> registration.close());
        });
  }

  /**
   * Also records in {@code cacheKeys}, at each payload's original position, the key to cache that
   * payload under when caching is enabled.
   */
  private List<IndexedValue<Payload>> createReferencePayloads(
      Batch<Payload> batch, List<StorageDriverClaim> claims, String[] cacheKeys) {
    if (claims == null || claims.size() != batch.size()) {
      throw new IllegalStateException(
          String.format(
              "Storage driver '%s' returned %d claims for %d payloads",
              batch.driver.getName(), claims == null ? 0 : claims.size(), batch.size()));
    }
    List<IndexedValue<Payload>> replacements = new ArrayList<>(claims.size());
    for (int batchIndex = 0; batchIndex < claims.size(); batchIndex++) {
      StorageDriverClaim claim = claims.get(batchIndex);
      if (claim == null) {
        throw new IllegalStateException(
            String.format(
                "Storage driver '%s' returned a null claim at index %d",
                batch.driver.getName(), batchIndex));
      }
      IndexedValue<Payload> indexedPayload = batch.get(batchIndex);
      if (isCachingEnabled()) {
        cacheKeys[indexedPayload.originalIndex] =
            PayloadCacheKeys.forClaim(batch.driver.getName(), claim);
      }
      replacements.add(
          new IndexedValue<>(
              indexedPayload.originalIndex,
              ExternalStorageReferences.toReferencePayload(
                  batch.driver.getName(), claim, indexedPayload.value.getSerializedSize())));
    }
    return replacements;
  }

  CompletableFuture<List<Payload>> retrieve(
      List<Payload> payloads, CancellationToken<CancellationException> cancellationToken) {
    return retrieve(payloads, cancellationToken, null);
  }

  CompletableFuture<List<Payload>> retrieve(
      List<Payload> payloads,
      CancellationToken<CancellationException> cancellationToken,
      @Nullable StorageOperationMetrics metrics) {
    List<RetrieveItem> items;
    try {
      items = parseRetrieveItems(payloads);
    } catch (RuntimeException e) {
      return failedFuture(e);
    }
    if (items.isEmpty()) {
      return CompletableFuture.completedFuture(payloads);
    }
    if (!isCachingEnabled()) {
      return runRetrieveDrivers(buildRetrieveBatches(items), cancellationToken, metrics)
          .thenApply(retrievedPayloads -> applyPayloadReplacements(payloads, retrievedPayloads));
    }
    return readFromCache(items)
        .thenCompose(
            cachedPayloads -> {
              List<IndexedValue<Payload>> hits = new ArrayList<>();
              List<RetrieveItem> misses = new ArrayList<>();
              for (int i = 0; i < items.size(); i++) {
                RetrieveItem item = items.get(i);
                Optional<Payload> cached = cachedPayloads.get(i);
                if (cached.isPresent()) {
                  hits.add(new IndexedValue<>(item.originalIndex, cached.get()));
                } else {
                  misses.add(item);
                }
              }
              List<Payload> withHits = applyPayloadReplacements(payloads, hits);
              if (misses.isEmpty()) {
                return CompletableFuture.completedFuture(withHits);
              }
              return runRetrieveDrivers(buildRetrieveBatches(misses), cancellationToken, metrics)
                  .thenCompose(
                      retrievedPayloads ->
                          writeToCache(misses, retrievedPayloads)
                              .thenApply(
                                  ignored ->
                                      applyPayloadReplacements(withHits, retrievedPayloads)));
            });
  }

  /** Parses every reference in {@code payloads}, failing on an unregistered driver name. */
  private List<RetrieveItem> parseRetrieveItems(List<Payload> payloads) {
    List<RetrieveItem> items = new ArrayList<>();
    for (int i = 0; i < payloads.size(); i++) {
      Payload payload = payloads.get(i);
      ExternalStorageReferences.ParsedReference reference =
          ExternalStorageReferences.tryParseReference(payload);
      if (reference == null) {
        continue;
      }
      StorageDriver driver = driversByName.get(reference.driverName);
      if (driver == null) {
        throw new IllegalStateException(
            "No storage driver registered with name '" + reference.driverName + "'");
      }
      String cacheKey =
          isCachingEnabled()
              ? PayloadCacheKeys.forClaim(reference.driverName, reference.claim)
              : null;
      items.add(new RetrieveItem(i, driver, reference.claim, cacheKey));
    }
    return items;
  }

  private static Map<String, Batch<StorageDriverClaim>> buildRetrieveBatches(
      List<RetrieveItem> items) {
    Map<String, Batch<StorageDriverClaim>> batches = new LinkedHashMap<>();
    for (RetrieveItem item : items) {
      batches
          .computeIfAbsent(item.driver.getName(), name -> new Batch<>(item.driver))
          .add(item.originalIndex, item.claim);
    }
    return batches;
  }

  /** Completes with one lookup result per item, in order. A failed lookup is a miss. */
  private CompletableFuture<List<Optional<Payload>>> readFromCache(List<RetrieveItem> items) {
    List<CompletableFuture<Optional<Payload>>> lookups = new ArrayList<>(items.size());
    for (RetrieveItem item : items) {
      lookups.add(getFromCache(item.cacheKey));
    }
    return CompletableFuture.allOf(lookups.toArray(new CompletableFuture<?>[0]))
        .thenApply(
            ignored -> {
              List<Optional<Payload>> results = new ArrayList<>(lookups.size());
              for (CompletableFuture<Optional<Payload>> lookup : lookups) {
                results.add(lookup.join());
              }
              return results;
            });
  }

  private CompletableFuture<Void> writeToCache(String[] cacheKeys, List<Payload> payloads) {
    List<CompletableFuture<Void>> writes = new ArrayList<>();
    for (int i = 0; i < cacheKeys.length; i++) {
      if (cacheKeys[i] != null) {
        writes.add(setInCache(cacheKeys[i], payloads.get(i)));
      }
    }
    return CompletableFuture.allOf(writes.toArray(new CompletableFuture<?>[0]));
  }

  private CompletableFuture<Void> writeToCache(
      List<RetrieveItem> items, List<IndexedValue<Payload>> retrievedPayloads) {
    Map<Integer, String> cacheKeysByOriginalIndex = new HashMap<>();
    for (RetrieveItem item : items) {
      cacheKeysByOriginalIndex.put(item.originalIndex, item.cacheKey);
    }
    List<CompletableFuture<Void>> writes = new ArrayList<>(retrievedPayloads.size());
    for (IndexedValue<Payload> retrieved : retrievedPayloads) {
      writes.add(
          setInCache(cacheKeysByOriginalIndex.get(retrieved.originalIndex), retrieved.value));
    }
    return CompletableFuture.allOf(writes.toArray(new CompletableFuture<?>[0]));
  }

  /** Never fails: a cache that throws or fails is treated as a miss. */
  private CompletableFuture<Optional<Payload>> getFromCache(String cacheKey) {
    CompletableFuture<Optional<Payload>> lookup;
    try {
      lookup = payloadCache.get(cacheKey);
    } catch (RuntimeException e) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    if (lookup == null) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    return lookup.handle(
        (payload, error) -> error == null && payload != null ? payload : Optional.empty());
  }

  /** Never fails: a cache that throws or fails is ignored. */
  private CompletableFuture<Void> setInCache(String cacheKey, Payload payload) {
    CompletableFuture<Boolean> write;
    try {
      write = payloadCache.set(cacheKey, payload);
    } catch (RuntimeException e) {
      return CompletableFuture.completedFuture(null);
    }
    if (write == null) {
      return CompletableFuture.completedFuture(null);
    }
    return write.handle((accepted, error) -> null);
  }

  private CompletableFuture<List<IndexedValue<Payload>>> runRetrieveDrivers(
      Map<String, Batch<StorageDriverClaim>> batches,
      CancellationToken<CancellationException> cancellationToken,
      @Nullable StorageOperationMetrics metrics) {
    return withDriverScope(
        cancellationToken,
        scope -> {
          StorageDriverRetrieveContext context =
              new StorageDriverRetrieveContextImpl(scope.token());
          for (Batch<StorageDriverClaim> batch : batches.values()) {
            long startNanos = System.nanoTime();
            CompletableFuture<List<Payload>> call = batch.driver.retrieve(context, batch.values());
            // Observes the call rather than wrapping it: the scope must keep the driver's own
            // future so cancellation still reaches the driver.
            call.whenComplete(
                (payloads, error) ->
                    recordBatch(
                        metrics,
                        batch.driver,
                        startNanos,
                        // The reference's recorded size is not part of the exchange contract, so
                        // measure what the driver actually returned.
                        error == null && payloads != null ? payloads.size() : 0,
                        error == null && payloads != null ? serializedSizeOf(payloads) : 0));
            scope.attach(call).map(payloads -> mapPayloadsToOriginalPositions(batch, payloads));
          }
          return scope.awaitAll(ListUtils::flatten);
        });
  }

  private static List<IndexedValue<Payload>> mapPayloadsToOriginalPositions(
      Batch<StorageDriverClaim> batch, List<Payload> payloads) {
    if (payloads == null || payloads.size() != batch.size()) {
      throw new IllegalStateException(
          String.format(
              "Storage driver '%s' returned %d payloads for %d claims",
              batch.driver.getName(), payloads == null ? 0 : payloads.size(), batch.size()));
    }
    List<IndexedValue<Payload>> replacements = new ArrayList<>(payloads.size());
    for (int batchIndex = 0; batchIndex < payloads.size(); batchIndex++) {
      Payload payload = payloads.get(batchIndex);
      if (payload == null) {
        throw new IllegalStateException(
            String.format(
                "Storage driver '%s' returned a null payload at index %d",
                batch.driver.getName(), batchIndex));
      }
      replacements.add(new IndexedValue<>(batch.get(batchIndex).originalIndex, payload));
    }
    return replacements;
  }

  private static long serializedSizeOf(List<Payload> payloads) {
    long total = 0;
    for (Payload payload : payloads) {
      total += payload.getSerializedSize();
    }
    return total;
  }

  /**
   * Records a batch once the driver settles, whether or not it succeeded: the time was spent either
   * way, and a driver that runs slowly and then fails is exactly when that attribution matters. The
   * count and size describe what actually moved, so a failed batch contributes zero of both while
   * still contributing its span.
   */
  private static void recordBatch(
      @Nullable StorageOperationMetrics metrics,
      StorageDriver driver,
      long startNanos,
      int payloadCount,
      long sizeBytes) {
    if (metrics != null) {
      metrics.recordBatch(payloadCount, sizeBytes, startNanos, System.nanoTime(), driver.getName());
    }
  }

  private static <T> CompletableFuture<T> failedFuture(Throwable t) {
    CompletableFuture<T> future = new CompletableFuture<>();
    future.completeExceptionally(t);
    return future;
  }

  private static List<Payload> applyPayloadReplacements(
      List<Payload> payloads, List<IndexedValue<Payload>> replacements) {
    Payload[] updatedPayloads = payloads.toArray(new Payload[0]);
    for (IndexedValue<Payload> replacement : replacements) {
      updatedPayloads[replacement.originalIndex] = replacement.value;
    }
    return Arrays.asList(updatedPayloads);
  }

  private static final class IndexedValue<T> {
    final int originalIndex;
    final T value;

    IndexedValue(int originalIndex, T value) {
      this.originalIndex = originalIndex;
      this.value = value;
    }
  }

  private static final class RetrieveItem {
    final int originalIndex;
    final StorageDriver driver;
    final StorageDriverClaim claim;

    /** Null when caching is off. */
    final @Nullable String cacheKey;

    RetrieveItem(
        int originalIndex,
        StorageDriver driver,
        StorageDriverClaim claim,
        @Nullable String cacheKey) {
      this.originalIndex = originalIndex;
      this.driver = driver;
      this.claim = claim;
      this.cacheKey = cacheKey;
    }
  }

  private static final class Batch<T> {
    final StorageDriver driver;
    private final List<IndexedValue<T>> indexedValues = new ArrayList<>();

    Batch(StorageDriver driver) {
      this.driver = driver;
    }

    void add(int originalIndex, T value) {
      indexedValues.add(new IndexedValue<>(originalIndex, value));
    }

    int size() {
      return indexedValues.size();
    }

    IndexedValue<T> get(int batchIndex) {
      return indexedValues.get(batchIndex);
    }

    List<T> values() {
      List<T> values = new ArrayList<>(indexedValues.size());
      for (IndexedValue<T> indexedValue : indexedValues) {
        values.add(indexedValue.value);
      }
      return values;
    }
  }
}
