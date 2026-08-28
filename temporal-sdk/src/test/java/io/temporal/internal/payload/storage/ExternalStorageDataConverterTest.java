package io.temporal.internal.payload.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.Failure;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.failure.ApplicationFailure;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
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

  @Test
  public void arrayFromPayloadsRoundTrips() {
    RecordingDriver driver = new RecordingDriver();
    DataConverter converter = resolving(driver, 0);

    Optional<Payloads> stored = converter.toPayloads("a", 42);

    Object[] values =
        converter.fromPayloads(
            stored,
            new Class<?>[] {String.class, Integer.class},
            new Type[] {String.class, Integer.class});

    assertEquals("a", values[0]);
    assertEquals(42, values[1]);
  }

  @Test
  public void arrayFromPayloadsWithAbsentContentUsesDefaults() {
    DataConverter converter = resolving(new RecordingDriver(), 0);

    Object[] values =
        converter.fromPayloads(
            Optional.empty(), new Class<?>[] {String.class}, new Type[] {String.class});

    assertNull(values[0]);
  }

  @Test
  public void arrayFromPayloadsDecodesThroughTheCodecInOneBatch() {
    RecordingDriver driver = new RecordingDriver();
    CountingCodec codec = new CountingCodec();
    DataConverter converter =
        new ExternalStorageDataConverter(
            new CodecDataConverter(plain, Collections.singletonList(codec)), runner(driver, 0));

    Optional<Payloads> stored = converter.toPayloads("a", "b", "c");

    assertEquals(1, codec.encodeCalls.get());
    assertTrue(driver.sawOnlyEncodedPayloads);

    Object[] values =
        converter.fromPayloads(
            stored,
            new Class<?>[] {String.class, String.class, String.class},
            new Type[] {String.class, String.class, String.class});

    assertArrayEquals(new Object[] {"a", "b", "c"}, values);
    assertEquals(1, codec.decodeCalls.get());
  }

  private DataConverter resolving(StorageDriver driver, int threshold) {
    return new ExternalStorageDataConverter(plain, runner(driver, threshold));
  }

  private static ExternalStorageRunner runner(StorageDriver driver, int threshold) {
    return ExternalStorageRunner.create(
        ExternalStorage.newBuilder().setDriver(driver).setPayloadSizeThreshold(threshold).build());
  }

  /** Prefixes payload data so an unencoded payload reaching the driver is detectable. */
  private static final class CountingCodec implements PayloadCodec {
    static final ByteString PREFIX = ByteString.copyFromUtf8("ENC:");

    final AtomicInteger encodeCalls = new AtomicInteger();
    final AtomicInteger decodeCalls = new AtomicInteger();

    @Override
    public List<Payload> encode(List<Payload> payloads) {
      encodeCalls.incrementAndGet();
      List<Payload> out = new ArrayList<>();
      for (Payload payload : payloads) {
        out.add(payload.toBuilder().setData(PREFIX.concat(payload.getData())).build());
      }
      return out;
    }

    @Override
    public List<Payload> decode(List<Payload> payloads) {
      decodeCalls.incrementAndGet();
      List<Payload> out = new ArrayList<>();
      for (Payload payload : payloads) {
        ByteString data = payload.getData();
        if (!data.startsWith(PREFIX)) {
          throw new IllegalStateException("payload reached decode without the codec prefix");
        }
        out.add(payload.toBuilder().setData(data.substring(PREFIX.size())).build());
      }
      return out;
    }
  }

  private static final class RecordingDriver implements StorageDriver {
    final Map<String, Payload> objects = new HashMap<>();
    final List<String> retrievedKeys = new ArrayList<>();
    volatile StorageDriverTargetInfo lastTarget;
    volatile boolean sawOnlyEncodedPayloads = true;
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
      for (Payload payload : payloads) {
        if (!payload.getData().startsWith(CountingCodec.PREFIX)) {
          sawOnlyEncodedPayloads = false;
        }
      }
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
