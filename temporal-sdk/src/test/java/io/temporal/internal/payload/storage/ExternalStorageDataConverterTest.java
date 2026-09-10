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
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class ExternalStorageDataConverterTest {

  private final DataConverter plain = DefaultDataConverter.newDefaultInstance();

  @Test
  public void payloadsRoundTripThroughStorage() {
    TestStorageDriver driver = TestStorageDriver.create();
    DataConverter converter = resolving(driver, 0);

    Optional<Payloads> stored = converter.toPayloads("a", "b");

    assertTrue(ExternalStorageReferences.isReference(stored.get().getPayloads(0)));
    assertTrue(ExternalStorageReferences.isReference(stored.get().getPayloads(1)));

    assertEquals("a", converter.fromPayloads(0, stored, String.class, String.class));
    assertEquals("b", converter.fromPayloads(1, stored, String.class, String.class));
  }

  @Test
  public void payloadsBelowThresholdStayInline() {
    TestStorageDriver driver = TestStorageDriver.create();
    DataConverter converter = resolving(driver, 1024);

    Optional<Payloads> stored = converter.toPayloads("small");

    assertFalse(ExternalStorageReferences.isReference(stored.get().getPayloads(0)));
    assertTrue(driver.storedPayloads().isEmpty());
    assertEquals("small", converter.fromPayloads(0, stored, String.class, String.class));
  }

  @Test
  public void readingOneArgumentDoesNotFetchTheRest() {
    TestStorageDriver driver = TestStorageDriver.create();
    DataConverter converter = resolving(driver, 0);

    Optional<Payloads> stored = converter.toPayloads("first", "second", "third");
    driver.retrievedKeys.clear();

    assertEquals("second", converter.fromPayloads(1, stored, String.class, String.class));

    assertEquals(1, driver.retrievedKeys.size());
  }

  @Test
  public void singlePayloadRoundTrips() {
    DataConverter converter = resolving(TestStorageDriver.create(), 0);

    Optional<Payload> stored = converter.toPayload("value");

    assertTrue(ExternalStorageReferences.isReference(stored.get()));
    assertEquals("value", converter.fromPayload(stored.get(), String.class, String.class));
  }

  @Test
  public void failureDetailsRoundTrip() {
    DataConverter converter = resolving(TestStorageDriver.create(), 0);

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
    TestStorageDriver driver = TestStorageDriver.create();
    StorageDriverWorkflowInfo target = new StorageDriverWorkflowInfo("ns", "wf-1", null, null);

    ExternalStorageDataConverter converter =
        new ExternalStorageDataConverter(plain, runner(driver, 0)).withStorageTarget(target);
    converter.toPayloads("x");

    assertEquals(target, driver.lastTarget());
  }

  @Test
  public void withoutATargetTheDriverSeesNone() {
    TestStorageDriver driver = TestStorageDriver.create();
    resolving(driver, 0).toPayloads("x");

    assertNull(driver.lastTarget());
  }

  @Test
  public void arrayFromPayloadsRoundTrips() {
    TestStorageDriver driver = TestStorageDriver.create();
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
    DataConverter converter = resolving(TestStorageDriver.create(), 0);

    Object[] values =
        converter.fromPayloads(
            Optional.empty(), new Class<?>[] {String.class}, new Type[] {String.class});

    assertNull(values[0]);
  }

  @Test
  public void arrayFromPayloadsDecodesThroughTheCodecInOneBatch() {
    TestStorageDriver driver = TestStorageDriver.create();
    CountingCodec codec = new CountingCodec();
    DataConverter converter = codecBacked(driver, codec);

    Optional<Payloads> stored = converter.toPayloads("a", "b", "c");
    assertEquals(1, codec.encodeCalls.get());

    Object[] values =
        converter.fromPayloads(
            stored,
            new Class<?>[] {String.class, String.class, String.class},
            new Type[] {String.class, String.class, String.class});

    assertArrayEquals(new Object[] {"a", "b", "c"}, values);
    assertEquals(1, codec.decodeCalls.get());
  }

  /**
   * A codec encrypts payloads, so a driver must never see the plaintext: conversion has to run
   * before the payload is handed to storage.
   */
  @Test
  public void driversOnlyEverSeeCodecEncodedPayloads() {
    TestStorageDriver driver = TestStorageDriver.create();
    CountingCodec codec = new CountingCodec();
    DataConverter converter = codecBacked(driver, codec);

    Optional<Payloads> stored = converter.toPayloads("a", "b", "c");

    assertEquals(3, driver.storedCount());
    for (Payload payload : driver.storedPayloads()) {
      String data = payload.getData().toStringUtf8();
      assertFalse(data.contains("\"a\""));
      assertFalse(data.contains("\"b\""));
      assertFalse(data.contains("\"c\""));
    }

    assertArrayEquals(
        new Object[] {"a", "b", "c"},
        converter.fromPayloads(
            stored,
            new Class<?>[] {String.class, String.class, String.class},
            new Type[] {String.class, String.class, String.class}));
  }

  private DataConverter codecBacked(StorageDriver driver, PayloadCodec codec) {
    return new ExternalStorageDataConverter(
        new CodecDataConverter(plain, Collections.singletonList(codec)), runner(driver, 0));
  }

  private DataConverter resolving(StorageDriver driver, int threshold) {
    return new ExternalStorageDataConverter(plain, runner(driver, threshold));
  }

  private static ExternalStorageRunner runner(StorageDriver driver, int threshold) {
    return ExternalStorageRunner.create(
        ExternalStorage.newBuilder().setDriver(driver).setPayloadSizeThreshold(threshold).build());
  }

  /** Obscures payload bytes so plaintext reaching a driver is detectable. */
  private static final class CountingCodec implements PayloadCodec {
    private static final byte KEY = 0x5A;

    final AtomicInteger encodeCalls = new AtomicInteger();
    final AtomicInteger decodeCalls = new AtomicInteger();

    @Override
    public List<Payload> encode(List<Payload> payloads) {
      encodeCalls.incrementAndGet();
      return apply(payloads);
    }

    @Override
    public List<Payload> decode(List<Payload> payloads) {
      decodeCalls.incrementAndGet();
      return apply(payloads);
    }

    private static List<Payload> apply(List<Payload> payloads) {
      List<Payload> out = new ArrayList<>();
      for (Payload payload : payloads) {
        byte[] bytes = payload.getData().toByteArray();
        for (int i = 0; i < bytes.length; i++) {
          bytes[i] ^= KEY;
        }
        out.add(payload.toBuilder().setData(ByteString.copyFrom(bytes)).build());
      }
      return out;
    }
  }
}
