package io.temporal.internal.payload.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.command.v1.StartChildWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Tests external storage message conversion. */
public class ExternalStorageMessageConverterTest {

  @Test
  public void storeAndRetrieveRoundTripsOverAMessage() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStorageMessageConverter converter = converter(driver, 0);
    Payloads message =
        Payloads.newBuilder().addPayloads(payload("a")).addPayloads(payload("b")).build();

    Payloads stored = converter.store(message, null).get();

    assertTrue(ExternalStorageReferences.isReference(stored.getPayloads(0)));
    assertTrue(ExternalStorageReferences.isReference(stored.getPayloads(1)));

    Payloads retrieved = converter.retrieve(stored).get();
    assertEquals(message, retrieved);
  }

  @Test
  public void walksNestedPayloads() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStorageMessageConverter converter = converter(driver, 0);
    Command command =
        Command.newBuilder()
            .setScheduleActivityTaskCommandAttributes(
                ScheduleActivityTaskCommandAttributes.newBuilder()
                    .setInput(Payloads.newBuilder().addPayloads(payload("deep"))))
            .build();

    Command stored = converter.store(command, null).get();

    Payload nested = stored.getScheduleActivityTaskCommandAttributes().getInput().getPayloads(0);
    assertTrue(ExternalStorageReferences.isReference(nested));
    assertEquals(command, converter.retrieve(stored).get());
  }

  @Test
  public void payloadBelowThresholdLeavesMessageUnchanged() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStorageMessageConverter converter = converter(driver, 1024);
    Payloads message = Payloads.newBuilder().addPayloads(payload("small")).build();

    Payloads stored = converter.store(message, null).get();

    assertFalse(ExternalStorageReferences.isReference(stored.getPayloads(0)));
    assertEquals(message, stored);
    assertTrue(driver.storeBatchSizes.isEmpty());
  }

  @Test
  public void searchAttributesAreNotOffloaded() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStorageMessageConverter converter = converter(driver, 0);
    Command command =
        Command.newBuilder()
            .setStartChildWorkflowExecutionCommandAttributes(
                StartChildWorkflowExecutionCommandAttributes.newBuilder()
                    .setInput(Payloads.newBuilder().addPayloads(payload("input")))
                    .setSearchAttributes(
                        SearchAttributes.newBuilder()
                            .putIndexedFields("k", payload("indexed-value"))))
            .build();

    Command stored = converter.store(command, null).get();

    StartChildWorkflowExecutionCommandAttributes attrs =
        stored.getStartChildWorkflowExecutionCommandAttributes();
    assertTrue(ExternalStorageReferences.isReference(attrs.getInput().getPayloads(0)));
    Payload indexed = attrs.getSearchAttributes().getIndexedFieldsOrThrow("k");
    assertFalse(ExternalStorageReferences.isReference(indexed));
    assertEquals(payload("indexed-value"), indexed);
  }

  @Test
  public void defaultConcurrencyBoundsOutstandingVisits() throws Exception {
    ControlledDriver driver = new ControlledDriver();
    ExternalStorageMessageConverter converter =
        ExternalStorageMessageConverter.create(
            ExternalStorageOptions.newBuilder()
                .setDriver(driver)
                .setPayloadSizeThreshold(0)
                .build());
    RespondWorkflowTaskCompletedRequest.Builder request =
        RespondWorkflowTaskCompletedRequest.newBuilder();
    for (int i = 0; i < 6; i++) {
      request.addCommands(
          Command.newBuilder()
              .setScheduleActivityTaskCommandAttributes(
                  ScheduleActivityTaskCommandAttributes.newBuilder()
                      .setInput(Payloads.newBuilder().addPayloads(payload("input-" + i)))));
    }

    CompletableFuture<RespondWorkflowTaskCompletedRequest> stored =
        converter.store(request.build(), null);

    assertEquals(3, driver.started.get());
    assertEquals(3, driver.pendingCount());
    for (int i = 0; i < 6; i++) {
      driver.completeNext();
      assertTrue(driver.pendingCount() <= 3);
    }
    stored.get();
    assertEquals(6, driver.started.get());
    assertEquals(0, driver.pendingCount());
  }

  @Test
  public void blockingStorePreservesInterruptStatus() throws Exception {
    InterruptibleDriver driver = new InterruptibleDriver();
    ExternalStorageMessageConverter converter =
        ExternalStorageMessageConverter.create(
            ExternalStorageOptions.newBuilder()
                .setDriver(driver)
                .setPayloadSizeThreshold(0)
                .build());
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicBoolean interrupted = new AtomicBoolean();
    Thread thread =
        new Thread(
            () -> {
              try {
                converter.storeBlocking(
                    Payloads.newBuilder().addPayloads(payload("input")).build(), null);
              } catch (Throwable e) {
                failure.set(e);
                interrupted.set(Thread.currentThread().isInterrupted());
              }
            });
    thread.start();
    assertTrue(driver.started.await(5, TimeUnit.SECONDS));

    thread.interrupt();
    thread.join(TimeUnit.SECONDS.toMillis(5));

    assertFalse("blocking store did not respond to interruption", thread.isAlive());
    assertTrue(failure.get() instanceof CompletionException);
    assertTrue(failure.get().getCause() instanceof InterruptedException);
    assertTrue(interrupted.get());
    driver.complete();
  }

  private static ExternalStorageMessageConverter converter(StorageDriver driver, int threshold) {
    ExternalStoragePayloadConverter payloadConverter =
        ExternalStoragePayloadConverter.fromOptions(
            ExternalStorageOptions.newBuilder()
                .setDriver(driver)
                .setPayloadSizeThreshold(threshold)
                .build());
    return new ExternalStorageMessageConverter(payloadConverter, 4);
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
