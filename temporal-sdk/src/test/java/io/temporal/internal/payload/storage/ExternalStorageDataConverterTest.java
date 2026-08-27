package io.temporal.internal.payload.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.Failure;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.failure.ApplicationFailure;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public class ExternalStorageDataConverterTest {

  private final DataConverter plain = DefaultDataConverter.newDefaultInstance();

  @Test
  public void payloadsRoundTripThroughStorage() {
    RecordingDriver driver = new RecordingDriver();
    DataConverter converter = resolving(driver, 0);

    Optional<Payloads> stored = converter.toPayloads("a", "b");

    assertTrue(ExternalStorageReferences.isReference(stored.get().getPayloads(0)));
    assertTrue(ExternalStorageReferences.isReference(stored.get().getPayloads(1)));

    assertEquals("a", converter.fromPayloads(0, stored, String.class, String.class));
    assertEquals("b", converter.fromPayloads(1, stored, String.class, String.class));
  }

  @Test
  public void payloadsBelowThresholdStayInline() {
    RecordingDriver driver = new RecordingDriver();
    DataConverter converter = resolving(driver, 1024);

    Optional<Payloads> stored = converter.toPayloads("small");

    assertFalse(ExternalStorageReferences.isReference(stored.get().getPayloads(0)));
    assertTrue(driver.objects.isEmpty());
    assertEquals("small", converter.fromPayloads(0, stored, String.class, String.class));
  }

  @Test
  public void readingOneArgumentDoesNotFetchTheRest() {
    RecordingDriver driver = new RecordingDriver();
    DataConverter converter = resolving(driver, 0);

    Optional<Payloads> stored = converter.toPayloads("first", "second", "third");
    driver.retrievedKeys.clear();

    assertEquals("second", converter.fromPayloads(1, stored, String.class, String.class));

    assertEquals(1, driver.retrievedKeys.size());
  }

  @Test
  public void singlePayloadRoundTrips() {
    DataConverter converter = resolving(new RecordingDriver(), 0);

    Optional<Payload> stored = converter.toPayload("value");

    assertTrue(ExternalStorageReferences.isReference(stored.get()));
    assertEquals("value", converter.fromPayload(stored.get(), String.class, String.class));
  }

  @Test
  public void failureDetailsRoundTrip() {
    DataConverter converter = resolving(new RecordingDriver(), 0);

    Failure failure =
        converter.exceptionToFailure(
            ApplicationFailure.newFailure("boom", "TestType", "detail-value"));

    Payload detail = failure.getApplicationFailureInfo().getDetails().getPayloads(0);
    assertTrue(ExternalStorageReferences.isReference(detail));

    RuntimeException restored = converter.failureToException(failure);
    assertTrue(restored instanceof ApplicationFailure);
    assertEquals("detail-value", ((ApplicationFailure) restored).getDetails().get(0, String.class));
  }

  @Test
  public void storageTargetReachesTheDriver() {
    RecordingDriver driver = new RecordingDriver();
    StorageDriverWorkflowInfo target = new StorageDriverWorkflowInfo("ns", "wf-1", null, null);

    ExternalStorageDataConverter converter =
        new ExternalStorageDataConverter(plain, runner(driver, 0)).withStorageTarget(target);
    converter.toPayloads("x");

    assertEquals(target, driver.lastTarget);
  }

  @Test
  public void withoutATargetTheDriverSeesNone() {
    RecordingDriver driver = new RecordingDriver();
    resolving(driver, 0).toPayloads("x");

    assertNull(driver.lastTarget);
  }

  private DataConverter resolving(StorageDriver driver, int threshold) {
    return new ExternalStorageDataConverter(plain, runner(driver, threshold));
  }

  private static ExternalStorageRunner runner(StorageDriver driver, int threshold) {
    return ExternalStorageRunner.create(
        ExternalStorage.newBuilder().setDriver(driver).setPayloadSizeThreshold(threshold).build());
  }

  private static final class RecordingDriver implements StorageDriver {
    final Map<String, Payload> objects = new HashMap<>();
    final List<String> retrievedKeys = new ArrayList<>();
    volatile StorageDriverTargetInfo lastTarget;
    private int counter = 0;

    @Override
    public String getName() {
      return "test";
    }

    @Override
    public String getType() {
      return "test.inmemory";
    }

    @Override
    public synchronized CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      lastTarget = context.getTarget();
      List<StorageDriverClaim> claims = new ArrayList<>();
      for (Payload payload : payloads) {
        String key = "k-" + (counter++);
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
        String key = claim.getClaimData().get("key");
        retrievedKeys.add(key);
        payloads.add(objects.get(key));
      }
      return CompletableFuture.completedFuture(payloads);
    }
  }
}
