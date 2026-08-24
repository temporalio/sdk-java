package io.temporal.internal.payload.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.CancellationToken;
import io.temporal.internal.concurrent.structured.CancelSource;
import io.temporal.payload.storage.ExternalStorageOptions;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverSelector;
import io.temporal.payload.storage.StorageDriverStoreContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Tests external storage payload-list conversion. */
public class ExternalStoragePayloadTransformerTest {

  @Test
  public void storesAndRetrievesRoundTrip() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStoragePayloadTransformer transformer = transformer(driver, 0);
    List<Payload> input = Arrays.asList(payload("a"), payload("b"));

    List<Payload> stored = transformer.store(input, null, CancellationToken.none()).get();

    assertEquals(2, stored.size());
    assertNotNull(ExternalStorageReferences.tryParseReference(stored.get(0)));
    assertNotNull(ExternalStorageReferences.tryParseReference(stored.get(1)));
    assertEquals(Collections.singletonList(2), driver.storeBatchSizes);
    assertEquals(
        input.get(0).getSerializedSize(), stored.get(0).getExternalPayloads(0).getSizeBytes());

    List<Payload> retrieved = transformer.retrieve(stored, CancellationToken.none()).get();
    assertEquals(input, retrieved);
    assertEquals(Collections.singletonList(2), driver.retrieveBatchSizes);
  }

  @Test
  public void payloadBelowThresholdStaysInline() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStoragePayloadTransformer transformer = transformer(driver, 100);
    Payload small = payload("x");
    Payload large = payload(repeat("y", 200));

    List<Payload> stored =
        transformer.store(Arrays.asList(small, large), null, CancellationToken.none()).get();

    assertNull(ExternalStorageReferences.tryParseReference(stored.get(0)));
    assertEquals(small, stored.get(0));
    assertNotNull(ExternalStorageReferences.tryParseReference(stored.get(1)));
    assertEquals(Collections.singletonList(1), driver.storeBatchSizes);
  }

  @Test
  public void selectorReturningNullKeepsInline() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStoragePayloadTransformer transformer =
        ExternalStoragePayloadTransformer.fromOptions(
            ExternalStorageOptions.newBuilder()
                .setDriver(driver)
                .setDriverSelector((context, payload) -> null)
                .setPayloadSizeThreshold(0)
                .build());

    List<Payload> stored =
        transformer
            .store(Collections.singletonList(payload("a")), null, CancellationToken.none())
            .get();

    assertEquals(payload("a"), stored.get(0));
    assertTrue(driver.storeBatchSizes.isEmpty());
  }

  @Test
  public void multipleDriversBatchPerDriverAndPreserveOrder() throws Exception {
    InMemoryDriver d1 = new InMemoryDriver("d1");
    InMemoryDriver d2 = new InMemoryDriver("d2");
    Map<String, StorageDriver> byPrefix = new HashMap<>();
    byPrefix.put("1", d1);
    byPrefix.put("2", d2);
    StorageDriverSelector selector =
        (context, payload) -> byPrefix.get(payload.getData().toStringUtf8().substring(0, 1));
    ExternalStoragePayloadTransformer transformer =
        ExternalStoragePayloadTransformer.fromOptions(
            ExternalStorageOptions.newBuilder()
                .setDrivers(Arrays.asList(d1, d2))
                .setDriverSelector(selector)
                .setPayloadSizeThreshold(0)
                .build());
    List<Payload> input = Arrays.asList(payload("1-a"), payload("2-b"), payload("1-c"));

    List<Payload> stored = transformer.store(input, null, CancellationToken.none()).get();

    assertEquals(Collections.singletonList(2), d1.storeBatchSizes);
    assertEquals(Collections.singletonList(1), d2.storeBatchSizes);
    assertEquals(input, transformer.retrieve(stored, CancellationToken.none()).get());
  }

  @Test
  public void arityMismatchFails() {
    StorageDriver driver =
        new FakeDriver("d1") {
          @Override
          public CompletableFuture<List<StorageDriverClaim>> store(
              StorageDriverStoreContext context, List<Payload> payloads) {
            return CompletableFuture.completedFuture(Collections.emptyList());
          }
        };
    ExternalStoragePayloadTransformer transformer = transformer(driver, 0);

    Throwable cause =
        causeOf(
            transformer.store(
                Collections.singletonList(payload("a")), null, CancellationToken.none()));
    assertTrue(cause instanceof IllegalStateException);
    assertTrue(cause.getMessage().contains("returned 0 claims for 1 payloads"));
  }

  @Test
  public void nullClaimFromDriverFails() {
    StorageDriver driver =
        new FakeDriver("d1") {
          @Override
          public CompletableFuture<List<StorageDriverClaim>> store(
              StorageDriverStoreContext context, List<Payload> payloads) {
            return CompletableFuture.completedFuture(Collections.singletonList(null));
          }
        };
    ExternalStoragePayloadTransformer transformer = transformer(driver, 0);

    Throwable cause =
        causeOf(
            transformer.store(
                Collections.singletonList(payload("a")), null, CancellationToken.none()));
    assertTrue(cause instanceof IllegalStateException);
    assertTrue(cause.getMessage().contains("returned a null claim at index 0"));
  }

  @Test
  public void nullPayloadFromDriverFails() {
    StorageDriver driver =
        new FakeDriver("d1") {
          @Override
          public CompletableFuture<List<Payload>> retrieve(
              StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
            return CompletableFuture.completedFuture(Collections.singletonList(null));
          }
        };
    ExternalStoragePayloadTransformer transformer = transformer(driver, 0);
    Payload reference =
        ExternalStorageReferences.toReferencePayload(
            "d1", new StorageDriverClaim(Collections.singletonMap("key", "k")), 1L);

    Throwable cause =
        causeOf(
            transformer.retrieve(Collections.singletonList(reference), CancellationToken.none()));
    assertTrue(cause instanceof IllegalStateException);
    assertTrue(cause.getMessage().contains("returned a null payload at index 0"));
  }

  @Test
  public void unknownDriverOnRetrieveFails() {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStoragePayloadTransformer transformer = transformer(driver, 0);
    Payload reference =
        ExternalStorageReferences.toReferencePayload(
            "ghost", new StorageDriverClaim(Collections.singletonMap("key", "k")), 1L);

    Throwable cause =
        causeOf(
            transformer.retrieve(Collections.singletonList(reference), CancellationToken.none()));
    assertTrue(cause instanceof IllegalStateException);
    assertTrue(cause.getMessage().contains("No storage driver registered with name 'ghost'"));
  }

  @Test
  public void selectorReturningUnregisteredDriverFails() {
    InMemoryDriver registered = new InMemoryDriver("d1");
    InMemoryDriver stranger = new InMemoryDriver("d2");
    ExternalStoragePayloadTransformer transformer =
        ExternalStoragePayloadTransformer.fromOptions(
            ExternalStorageOptions.newBuilder()
                .setDriver(registered)
                .setDriverSelector((context, payload) -> stranger)
                .setPayloadSizeThreshold(0)
                .build());

    Throwable cause =
        causeOf(
            transformer.store(
                Collections.singletonList(payload("a")), null, CancellationToken.none()));
    assertTrue(cause instanceof IllegalStateException);
    assertTrue(cause.getMessage().contains("not registered"));
  }

  @Test
  public void firstErrorRequestsCancellationOfOutstandingDriverCalls() {
    CompletableFuture<List<StorageDriverClaim>> inFlight = new CompletableFuture<>();
    CompletableFuture<List<StorageDriverClaim>> failing = new CompletableFuture<>();
    AtomicBoolean cancellationRequested = new AtomicBoolean(false);
    StorageDriver slow =
        new FakeDriver("d1") {
          @Override
          public CompletableFuture<List<StorageDriverClaim>> store(
              StorageDriverStoreContext context, List<Payload> payloads) {
            context.getCancellationToken().onCancel(() -> cancellationRequested.set(true));
            return inFlight;
          }
        };
    StorageDriver doomed = controlledStore("d2", failing);
    Map<String, StorageDriver> byPrefix = new HashMap<>();
    byPrefix.put("1", slow);
    byPrefix.put("2", doomed);
    ExternalStoragePayloadTransformer transformer =
        ExternalStoragePayloadTransformer.fromOptions(
            ExternalStorageOptions.newBuilder()
                .setDrivers(Arrays.asList(slow, doomed))
                .setDriverSelector(
                    (context, payload) ->
                        byPrefix.get(payload.getData().toStringUtf8().substring(0, 1)))
                .setPayloadSizeThreshold(0)
                .build());

    CompletableFuture<List<Payload>> result =
        transformer.store(
            Arrays.asList(payload("1-a"), payload("2-b")), null, CancellationToken.none());
    assertFalse(result.isDone());

    failing.completeExceptionally(new RuntimeException("boom"));

    assertTrue(result.isCompletedExceptionally());
    assertTrue(cancellationRequested.get());
    assertTrue(inFlight.isCancelled());
  }

  @Test
  public void callerCancellationRequestsCancellationOfInFlightStore() {
    CompletableFuture<List<StorageDriverClaim>> inFlight = new CompletableFuture<>();
    AtomicBoolean cancellationRequested = new AtomicBoolean(false);
    StorageDriver slow =
        new FakeDriver("d1") {
          @Override
          public CompletableFuture<List<StorageDriverClaim>> store(
              StorageDriverStoreContext context, List<Payload> payloads) {
            context.getCancellationToken().onCancel(() -> cancellationRequested.set(true));
            return inFlight;
          }
        };
    CancelSource<CancellationException> caller = new CancelSource<>(CancellationException::new);

    CompletableFuture<List<Payload>> result =
        transformer(slow, 0).store(Collections.singletonList(payload("a")), null, caller.token());
    assertFalse(result.isDone());

    caller.cancel();

    assertTrue(cancellationRequested.get());
    assertTrue(inFlight.isCancelled());
    assertTrue(result.isCompletedExceptionally());
  }

  @Test
  public void callerCancellationRequestsCancellationOfInFlightRetrieve() {
    CompletableFuture<List<Payload>> inFlight = new CompletableFuture<>();
    AtomicBoolean cancellationRequested = new AtomicBoolean(false);
    StorageDriver slow =
        new FakeDriver("d1") {
          @Override
          public CompletableFuture<List<Payload>> retrieve(
              StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
            context.getCancellationToken().onCancel(() -> cancellationRequested.set(true));
            return inFlight;
          }
        };
    CancelSource<CancellationException> caller = new CancelSource<>(CancellationException::new);
    Payload reference =
        ExternalStorageReferences.toReferencePayload(
            "d1", new StorageDriverClaim(Collections.singletonMap("key", "k")), 1L);

    CompletableFuture<List<Payload>> result =
        transformer(slow, 0).retrieve(Collections.singletonList(reference), caller.token());
    assertFalse(result.isDone());

    caller.cancel();

    assertTrue(cancellationRequested.get());
    assertTrue(inFlight.isCancelled());
    assertTrue(result.isCompletedExceptionally());
  }

  @Test
  public void selectorObservesCallerCancellationToken() {
    InMemoryDriver driver = new InMemoryDriver("d1");
    CancelSource<CancellationException> caller = new CancelSource<>(CancellationException::new);
    AtomicReference<CancellationToken<CancellationException>> observed = new AtomicReference<>();
    ExternalStoragePayloadTransformer transformer =
        ExternalStoragePayloadTransformer.fromOptions(
            ExternalStorageOptions.newBuilder()
                .setDriver(driver)
                .setDriverSelector(
                    (context, payload) -> {
                      observed.set(context.getCancellationToken());
                      return driver;
                    })
                .setPayloadSizeThreshold(0)
                .build());

    transformer.store(Collections.singletonList(payload("a")), null, caller.token());

    assertSame(caller.token(), observed.get());
  }

  private static ExternalStoragePayloadTransformer transformer(
      StorageDriver driver, int threshold) {
    return ExternalStoragePayloadTransformer.fromOptions(
        ExternalStorageOptions.newBuilder()
            .setDriver(driver)
            .setPayloadSizeThreshold(threshold)
            .build());
  }

  private static Payload payload(String data) {
    return Payload.newBuilder().setData(ByteString.copyFromUtf8(data)).build();
  }

  private static String repeat(String s, int n) {
    StringBuilder sb = new StringBuilder(s.length() * n);
    for (int i = 0; i < n; i++) {
      sb.append(s);
    }
    return sb.toString();
  }

  private static Throwable causeOf(CompletableFuture<?> future) {
    try {
      future.get();
      fail("expected failure");
      return null;
    } catch (ExecutionException e) {
      return e.getCause();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  private static StorageDriver controlledStore(
      String name, CompletableFuture<List<StorageDriverClaim>> future) {
    return new FakeDriver(name) {
      @Override
      public CompletableFuture<List<StorageDriverClaim>> store(
          StorageDriverStoreContext context, List<Payload> payloads) {
        return future;
      }
    };
  }

  private static class FakeDriver implements StorageDriver {
    private final String name;

    FakeDriver(String name) {
      this.name = name;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getType() {
      return "test.fake";
    }

    @Override
    public CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      throw new UnsupportedOperationException();
    }
  }

  private static class InMemoryDriver extends FakeDriver {
    final Map<String, Payload> objects = new HashMap<>();
    final List<Integer> storeBatchSizes = new ArrayList<>();
    final List<Integer> retrieveBatchSizes = new ArrayList<>();
    private int counter = 0;

    InMemoryDriver(String name) {
      super(name);
    }

    @Override
    public CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      storeBatchSizes.add(payloads.size());
      List<StorageDriverClaim> claims = new ArrayList<>();
      for (Payload payload : payloads) {
        String key = getName() + "-" + (counter++);
        objects.put(key, payload);
        claims.add(new StorageDriverClaim(Collections.singletonMap("key", key)));
      }
      return CompletableFuture.completedFuture(claims);
    }

    @Override
    public CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      retrieveBatchSizes.add(claims.size());
      List<Payload> payloads = new ArrayList<>();
      for (StorageDriverClaim claim : claims) {
        payloads.add(objects.get(claim.getClaimData().get("key")));
      }
      return CompletableFuture.completedFuture(payloads);
    }
  }
}
