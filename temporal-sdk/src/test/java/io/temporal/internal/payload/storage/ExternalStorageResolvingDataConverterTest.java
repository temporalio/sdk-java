package io.temporal.internal.payload.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.common.CancellationToken;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.payload.context.WorkflowSerializationContext;
import io.temporal.payload.storage.ExternalStorageOptions;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class ExternalStorageResolvingDataConverterTest {

  private final DataConverter delegate = DefaultDataConverter.newDefaultInstance();

  @Test
  public void resolvesReferenceBeforeDecoding() {
    CountingDriver driver = new CountingDriver();
    ExternalStorage storage = storage(driver);
    Payload reference = offload("hello", storage);

    DataConverter resolving = new ExternalStorageResolvingDataConverter(delegate, storage);
    String value = resolving.fromPayload(reference, String.class, String.class);

    assertEquals("hello", value);
    assertEquals(1, driver.retrievedPayloads.get());
  }

  @Test
  public void passesInlinePayloadThroughWithoutFetching() {
    CountingDriver driver = new CountingDriver();
    ExternalStorage storage = storage(driver);
    Payload inline = delegate.toPayload("world").get();

    DataConverter resolving = new ExternalStorageResolvingDataConverter(delegate, storage);
    String value = resolving.fromPayload(inline, String.class, String.class);

    assertEquals("world", value);
    assertEquals(0, driver.retrievedPayloads.get());
  }

  @Test
  public void resolutionSurvivesWithContext() {
    CountingDriver driver = new CountingDriver();
    ExternalStorage storage = storage(driver);
    Payload reference = offload("scoped", storage);

    DataConverter resolving =
        new ExternalStorageResolvingDataConverter(delegate, storage)
            .withContext(new WorkflowSerializationContext("ns", "wf-id"));
    String value = resolving.fromPayload(reference, String.class, String.class);

    assertEquals("scoped", value);
    assertEquals(1, driver.retrievedPayloads.get());
  }

  private static ExternalStorage storage(StorageDriver driver) {
    ExternalStoragePayloadTransformer transformer =
        ExternalStoragePayloadTransformer.fromOptions(
            ExternalStorageOptions.newBuilder()
                .setDriver(driver)
                .setPayloadSizeThreshold(0)
                .build());
    return new ExternalStorage(transformer, 4);
  }

  private Payload offload(String value, ExternalStorage storage) {
    Payloads stored =
        storage
            .store(
                Payloads.newBuilder().addPayloads(delegate.toPayload(value).get()).build(),
                null,
                CancellationToken.none())
            .join();
    Payload reference = stored.getPayloads(0);
    assertTrue(ExternalStorageReferences.isReference(reference));
    return reference;
  }

  private static final class CountingDriver implements StorageDriver {
    private final Map<String, Payload> objects = new HashMap<>();
    private final AtomicInteger counter = new AtomicInteger();
    final AtomicInteger retrievedPayloads = new AtomicInteger();

    @Override
    public String getName() {
      return "counting";
    }

    @Override
    public String getType() {
      return "test.counting";
    }

    @Override
    public synchronized CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      List<StorageDriverClaim> claims = new ArrayList<>();
      for (Payload payload : payloads) {
        String key = "k-" + counter.getAndIncrement();
        objects.put(key, payload);
        claims.add(new StorageDriverClaim(Collections.singletonMap("key", key)));
      }
      return CompletableFuture.completedFuture(claims);
    }

    @Override
    public synchronized CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      List<Payload> payloads = new ArrayList<>();
      for (StorageDriverClaim claim : claims) {
        payloads.add(objects.get(claim.getClaimData().get("key")));
        retrievedPayloads.incrementAndGet();
      }
      return CompletableFuture.completedFuture(payloads);
    }
  }
}
