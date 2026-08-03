package io.temporal.internal.payload.storage;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.CancellationToken;
import io.temporal.internal.common.ListUtils;
import io.temporal.internal.concurrent.structured.TaskScope;
import io.temporal.payload.storage.ExternalStorageOptions;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverSelector;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

  static ExternalStoragePayloadTransformer fromOptions(ExternalStorageOptions options) {
    Map<String, StorageDriver> driversByName = new LinkedHashMap<>();
    for (StorageDriver driver : options.getDrivers()) {
      driversByName.put(driver.getName(), driver);
    }
    return new ExternalStoragePayloadTransformer(
        driversByName, options.getDriverSelector(), options.getPayloadSizeThreshold());
  }

  private ExternalStoragePayloadTransformer(
      Map<String, StorageDriver> driversByName,
      StorageDriverSelector selector,
      int payloadSizeThreshold) {
    this.driversByName = driversByName;
    this.selector = selector;
    this.payloadSizeThreshold = payloadSizeThreshold;
  }

  CompletableFuture<List<Payload>> store(
      List<Payload> payloads,
      @Nullable StorageDriverTargetInfo target,
      CancellationToken<CancellationException> cancellationToken) {
    StorageDriverStoreContext context =
        new StorageDriverStoreContextImpl(target, cancellationToken);
    Map<String, Batch<Payload>> batches;
    try {
      batches = buildStoreBatches(payloads, context);
    } catch (RuntimeException e) {
      return failedFuture(e);
    }
    if (batches.isEmpty()) {
      return CompletableFuture.completedFuture(payloads);
    }
    return runStoreDrivers(batches, target, cancellationToken)
        .thenApply(referencePayloads -> applyPayloadReplacements(payloads, referencePayloads));
  }

  private Map<String, Batch<Payload>> buildStoreBatches(
      List<Payload> payloads, StorageDriverStoreContext context) {
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
      CancellationToken<CancellationException> cancellationToken) {
    return withDriverScope(
        cancellationToken,
        scope -> {
          StorageDriverStoreContext context =
              new StorageDriverStoreContextImpl(target, scope.token());
          for (Batch<Payload> batch : batches.values()) {
            scope
                .attach(batch.driver.store(context, batch.values()))
                .map(claims -> createReferencePayloads(batch, claims));
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

  private static List<IndexedValue<Payload>> createReferencePayloads(
      Batch<Payload> batch, List<StorageDriverClaim> claims) {
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
    Map<String, Batch<StorageDriverClaim>> batches;
    try {
      batches = buildRetrieveBatches(payloads);
    } catch (RuntimeException e) {
      return failedFuture(e);
    }
    if (batches.isEmpty()) {
      return CompletableFuture.completedFuture(payloads);
    }
    return runRetrieveDrivers(batches, cancellationToken)
        .thenApply(retrievedPayloads -> applyPayloadReplacements(payloads, retrievedPayloads));
  }

  private Map<String, Batch<StorageDriverClaim>> buildRetrieveBatches(List<Payload> payloads) {
    Map<String, Batch<StorageDriverClaim>> batches = new LinkedHashMap<>();
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
      batches
          .computeIfAbsent(reference.driverName, name -> new Batch<>(driver))
          .add(i, reference.claim);
    }
    return batches;
  }

  private CompletableFuture<List<IndexedValue<Payload>>> runRetrieveDrivers(
      Map<String, Batch<StorageDriverClaim>> batches,
      CancellationToken<CancellationException> cancellationToken) {
    return withDriverScope(
        cancellationToken,
        scope -> {
          StorageDriverRetrieveContext context =
              new StorageDriverRetrieveContextImpl(scope.token());
          for (Batch<StorageDriverClaim> batch : batches.values()) {
            scope
                .attach(batch.driver.retrieve(context, batch.values()))
                .map(payloads -> mapPayloadsToOriginalPositions(batch, payloads));
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
