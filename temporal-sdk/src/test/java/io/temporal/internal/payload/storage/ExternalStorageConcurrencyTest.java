package io.temporal.internal.payload.storage;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.CancellationToken;
import io.temporal.internal.common.AsyncSemaphore;
import io.temporal.internal.concurrent.structured.CancelSource;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import org.junit.Test;

/** Concurrency limits and the limiter handed to drivers. */
public class ExternalStorageConcurrencyTest {

  /** Takes a permit around every request and blocks, so peak concurrency is observable. */
  private static final class PermittingDriver implements StorageDriver {
    private final CompletableFuture<Void> gate = new CompletableFuture<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();

    @Nonnull
    @Override
    public String getName() {
      return "permitting";
    }

    @Nonnull
    @Override
    public String getType() {
      return "permitting";
    }

    private <T> CompletableFuture<T> hold(T value) {
      int current = inFlight.incrementAndGet();
      peak.accumulateAndGet(current, Math::max);
      return gate.thenApply(
          ignored -> {
            inFlight.decrementAndGet();
            return value;
          });
    }

    @Nonnull
    @Override
    public CompletableFuture<List<StorageDriverClaim>> store(
        @Nonnull StorageDriverStoreContext context, @Nonnull List<Payload> payloads) {
      List<CompletableFuture<StorageDriverClaim>> futures = new ArrayList<>();
      for (int i = 0; i < payloads.size(); i++) {
        futures.add(
            context
                .getLimiter()
                .permit(
                    payloads.get(i),
                    () -> hold(new StorageDriverClaim(Collections.singletonMap("id", "x")))));
      }
      return allOf(futures);
    }

    @Nonnull
    @Override
    public CompletableFuture<List<Payload>> retrieve(
        @Nonnull StorageDriverRetrieveContext context, @Nonnull List<StorageDriverClaim> claims) {
      List<CompletableFuture<Payload>> futures = new ArrayList<>();
      for (int i = 0; i < claims.size(); i++) {
        futures.add(
            context.getLimiter().permit(claims.get(i), () -> hold(Payload.getDefaultInstance())));
      }
      return allOf(futures);
    }

    private static <T> CompletableFuture<List<T>> allOf(List<CompletableFuture<T>> futures) {
      return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
          .thenApply(
              ignored -> {
                List<T> out = new ArrayList<>(futures.size());
                for (CompletableFuture<T> f : futures) {
                  out.add(f.join());
                }
                return out;
              });
    }
  }

  private static ExternalStoragePayloadTransformer transformer(StorageDriver driver) {
    return ExternalStoragePayloadTransformer.fromOptions(
        ExternalStorage.newBuilder().setDriver(driver).setPayloadSizeThreshold(0).build());
  }

  private static List<Payload> payloads(int count) {
    List<Payload> out = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      out.add(
          Payload.newBuilder().setData(com.google.protobuf.ByteString.copyFromUtf8("x")).build());
    }
    return out;
  }

  private static void awaitPeak(PermittingDriver driver, int expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (driver.peak.get() < expected && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    Thread.sleep(50);
  }

  @Test
  public void maxOperationsPerMessageBoundsOperations() throws Exception {
    PermittingDriver driver = new PermittingDriver();
    MessageStorageLimits limits = new MessageStorageLimits(3, new AsyncSemaphore(100));

    CompletableFuture<List<Payload>> result =
        transformer(driver).store(payloads(6), null, CancellationToken.none(), limits);

    awaitPeak(driver, 3);
    assertEquals(3, driver.peak.get());

    driver.gate.complete(null);
    result.get(5, TimeUnit.SECONDS);
    assertEquals(3, driver.peak.get());
  }

  @Test
  public void eachMessageGetsItsOwnBudget() throws Exception {
    PermittingDriver driver = new PermittingDriver();
    AsyncSemaphore shared = new AsyncSemaphore(100);
    ExternalStoragePayloadTransformer transformer = transformer(driver);

    List<CompletableFuture<List<Payload>>> messages =
        Arrays.asList(
            transformer.store(
                payloads(3), null, CancellationToken.none(), new MessageStorageLimits(2, shared)),
            transformer.store(
                payloads(3), null, CancellationToken.none(), new MessageStorageLimits(2, shared)));

    awaitPeak(driver, 4);
    assertEquals("two messages at 2 each, not 2 shared", 4, driver.peak.get());

    driver.gate.complete(null);
    for (CompletableFuture<List<Payload>> message : messages) {
      message.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void maxDriverOperationsSharedAcrossMessages() throws Exception {
    PermittingDriver driver = new PermittingDriver();
    AsyncSemaphore shared = new AsyncSemaphore(3);
    ExternalStoragePayloadTransformer transformer = transformer(driver);

    List<CompletableFuture<List<Payload>>> messages =
        Arrays.asList(
            transformer.store(
                payloads(4), null, CancellationToken.none(), new MessageStorageLimits(10, shared)),
            transformer.store(
                payloads(4), null, CancellationToken.none(), new MessageStorageLimits(10, shared)));

    awaitPeak(driver, 3);
    assertEquals("one instance-wide budget spans both messages", 3, driver.peak.get());

    driver.gate.complete(null);
    for (CompletableFuture<List<Payload>> message : messages) {
      message.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void cancellingQueuedPermitDoesNotStartOperation() throws Exception {
    AsyncSemaphore shared = new AsyncSemaphore(1);
    shared.acquire();
    MessageStorageLimits limits = new MessageStorageLimits(1, shared);
    AtomicInteger operations = new AtomicInteger();
    CancelSource<CancellationException> cancellation =
        new CancelSource<>(CancellationException::new);

    CompletableFuture<String> queued =
        limits
            .<String>newLimiter(cancellation.token())
            .permit(
                "item",
                () -> {
                  operations.incrementAndGet();
                  return CompletableFuture.completedFuture("completed");
                });

    cancellation.cancel();
    shared.release();

    assertEquals(0, operations.get());
    assertEquals(
        "next",
        limits
            .<String>newLimiter(CancellationToken.none())
            .permit("next", () -> CompletableFuture.completedFuture("next"))
            .get(5, TimeUnit.SECONDS));
  }
}
