package io.temporal.internal.payload.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.command.v1.StartChildWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.common.CancellationToken;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Tests external storage message conversion. */
public class ExternalStorageMessageTransformerTest {

  @Test
  public void storeAndRetrieveRoundTripsOverAMessage() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStorageMessageTransformer transformer = transformer(driver, 0);
    Payloads message =
        Payloads.newBuilder().addPayloads(payload("a")).addPayloads(payload("b")).build();

    Payloads stored = transformer.store(message, null, CancellationToken.none()).get();

    assertNotNull(ExternalStorageReferences.tryParseReference(stored.getPayloads(0)));
    assertNotNull(ExternalStorageReferences.tryParseReference(stored.getPayloads(1)));

    Payloads retrieved = transformer.retrieve(stored, CancellationToken.none()).get();
    assertEquals(message, retrieved);
  }

  @Test
  public void walksNestedPayloads() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStorageMessageTransformer transformer = transformer(driver, 0);
    Command command =
        Command.newBuilder()
            .setScheduleActivityTaskCommandAttributes(
                ScheduleActivityTaskCommandAttributes.newBuilder()
                    .setInput(Payloads.newBuilder().addPayloads(payload("deep"))))
            .build();

    Command stored = transformer.store(command, null, CancellationToken.none()).get();

    Payload nested = stored.getScheduleActivityTaskCommandAttributes().getInput().getPayloads(0);
    assertNotNull(ExternalStorageReferences.tryParseReference(nested));
    assertEquals(command, transformer.retrieve(stored, CancellationToken.none()).get());
  }

  @Test
  public void payloadBelowThresholdLeavesMessageUnchanged() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStorageMessageTransformer transformer = transformer(driver, 1024);
    Payloads message = Payloads.newBuilder().addPayloads(payload("small")).build();

    Payloads stored = transformer.store(message, null, CancellationToken.none()).get();

    assertNull(ExternalStorageReferences.tryParseReference(stored.getPayloads(0)));
    assertEquals(message, stored);
    assertTrue(driver.storeBatchSizes.isEmpty());
  }

  @Test
  public void searchAttributesAreNotOffloaded() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStorageMessageTransformer transformer = transformer(driver, 0);
    Command command =
        Command.newBuilder()
            .setStartChildWorkflowExecutionCommandAttributes(
                StartChildWorkflowExecutionCommandAttributes.newBuilder()
                    .setInput(Payloads.newBuilder().addPayloads(payload("input")))
                    .setSearchAttributes(
                        SearchAttributes.newBuilder()
                            .putIndexedFields("k", payload("indexed-value"))))
            .build();

    Command stored = transformer.store(command, null, CancellationToken.none()).get();

    StartChildWorkflowExecutionCommandAttributes attrs =
        stored.getStartChildWorkflowExecutionCommandAttributes();
    assertNotNull(ExternalStorageReferences.tryParseReference(attrs.getInput().getPayloads(0)));
    Payload indexed = attrs.getSearchAttributes().getIndexedFieldsOrThrow("k");
    assertNull(ExternalStorageReferences.tryParseReference(indexed));
    assertEquals(payload("indexed-value"), indexed);
  }

  private static ExternalStorageMessageTransformer transformer(
      StorageDriver driver, int threshold) {
    ExternalStoragePayloadTransformer payloadTransformer =
        ExternalStoragePayloadTransformer.fromOptions(
            ExternalStorageOptions.newBuilder()
                .setDriver(driver)
                .setPayloadSizeThreshold(threshold)
                .build());
    return new ExternalStorageMessageTransformer(payloadTransformer, 4);
  }

  private static Payload payload(String data) {
    return Payload.newBuilder().setData(ByteString.copyFromUtf8(data)).build();
  }

  private static final class InMemoryDriver implements StorageDriver {
    private final String name;
    private final Map<String, Payload> objects = new HashMap<>();
    final List<Integer> storeBatchSizes = new ArrayList<>();
    private int counter = 0;

    InMemoryDriver(String name) {
      this.name = name;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getType() {
      return "test.inmemory";
    }

    @Override
    public synchronized CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      storeBatchSizes.add(payloads.size());
      List<StorageDriverClaim> claims = new ArrayList<>();
      for (Payload payload : payloads) {
        String key = name + "-" + (counter++);
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
      }
      return CompletableFuture.completedFuture(payloads);
    }
  }

  private static final class ControlledDriver implements StorageDriver {
    private final List<PendingStore> pending = new ArrayList<>();
    private final AtomicInteger started = new AtomicInteger();

    @Override
    public String getName() {
      return "controlled";
    }

    @Override
    public String getType() {
      return "test.controlled";
    }

    @Override
    public synchronized CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      started.incrementAndGet();
      CompletableFuture<List<StorageDriverClaim>> future = new CompletableFuture<>();
      pending.add(new PendingStore(future, payloads.size()));
      return future;
    }

    synchronized int pendingCount() {
      return pending.size();
    }

    synchronized void completeNext() {
      PendingStore store = pending.remove(0);
      List<StorageDriverClaim> claims = new ArrayList<>(store.payloadCount);
      for (int i = 0; i < store.payloadCount; i++) {
        claims.add(new StorageDriverClaim(Collections.emptyMap()));
      }
      store.future.complete(claims);
    }

    @Override
    public CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class PendingStore {
    private final CompletableFuture<List<StorageDriverClaim>> future;
    private final int payloadCount;

    private PendingStore(CompletableFuture<List<StorageDriverClaim>> future, int payloadCount) {
      this.future = future;
      this.payloadCount = payloadCount;
    }
  }

  private static final class InterruptibleDriver implements StorageDriver {
    private final CountDownLatch started = new CountDownLatch(1);
    private final CompletableFuture<List<StorageDriverClaim>> future = new CompletableFuture<>();

    @Override
    public String getName() {
      return "interruptible";
    }

    @Override
    public String getType() {
      return "test.interruptible";
    }

    @Override
    public CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      started.countDown();
      return future;
    }

    void complete() {
      future.complete(Collections.singletonList(new StorageDriverClaim(Collections.emptyMap())));
    }

    @Override
    public CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      throw new UnsupportedOperationException();
    }
  }
}
