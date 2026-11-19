package io.temporal.payload.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import java.util.Optional;
import org.junit.Test;

public class InMemoryPayloadCacheTest {

  @Test
  public void returnsAStoredPayload() {
    InMemoryPayloadCache cache = new InMemoryPayloadCache();
    Payload payload = payloadOfSize(100);

    assertTrue(cache.set("k", payload).join());

    assertEquals(Optional.of(payload), cache.get("k").join());
  }

  @Test
  public void returnsEmptyOnAMiss() {
    assertEquals(Optional.empty(), new InMemoryPayloadCache().get("missing").join());
  }

  @Test
  public void storesACopyOfThePayload() {
    InMemoryPayloadCache cache = new InMemoryPayloadCache();
    Payload payload = payloadOfSize(100);

    cache.set("k", payload).join();

    assertNotSame(payload, cache.get("k").join().get());
  }

  @Test
  public void usesDefaultLimits() {
    InMemoryPayloadCache cache = new InMemoryPayloadCache();

    assertEquals(64L * 1024 * 1024, cache.getMaxTotalBytes());
    assertEquals(10_000, cache.getMaxEntries());
  }

  @Test
  public void declinesAPayloadLargerThanTheByteLimit() {
    Payload payload = payloadOfSize(100);
    InMemoryPayloadCache cache =
        InMemoryPayloadCache.newBuilder().setMaxTotalBytes(payload.getSerializedSize() - 1).build();

    assertFalse(cache.set("k", payload).join());
    assertEquals(Optional.empty(), cache.get("k").join());
  }

  @Test
  public void acceptsAPayloadExactlyAtTheByteLimit() {
    Payload payload = payloadOfSize(100);
    InMemoryPayloadCache cache =
        InMemoryPayloadCache.newBuilder().setMaxTotalBytes(payload.getSerializedSize()).build();

    assertTrue(cache.set("k", payload).join());
  }

  @Test
  public void declinesEveryPayloadWhenTheEntryLimitIsZero() {
    InMemoryPayloadCache cache = InMemoryPayloadCache.newBuilder().setMaxEntries(0).build();

    assertFalse(cache.set("k", payloadOfSize(1)).join());
    assertEquals(Optional.empty(), cache.get("k").join());
  }

  @Test
  public void evictsTheLeastRecentlyUsedEntryToStayUnderTheByteLimit() {
    Payload payload = payloadOfSize(100);
    InMemoryPayloadCache cache =
        InMemoryPayloadCache.newBuilder()
            .setMaxTotalBytes(2L * payload.getSerializedSize())
            .build();

    cache.set("a", payload).join();
    cache.set("b", payload).join();
    cache.set("c", payload).join();

    assertEquals(Optional.empty(), cache.get("a").join());
    assertTrue(cache.get("b").join().isPresent());
    assertTrue(cache.get("c").join().isPresent());
  }

  @Test
  public void evictsTheLeastRecentlyUsedEntryToStayUnderTheEntryLimit() {
    InMemoryPayloadCache cache = InMemoryPayloadCache.newBuilder().setMaxEntries(2).build();

    cache.set("a", payloadOfSize(1)).join();
    cache.set("b", payloadOfSize(1)).join();
    cache.set("c", payloadOfSize(1)).join();

    assertEquals(Optional.empty(), cache.get("a").join());
    assertTrue(cache.get("b").join().isPresent());
    assertTrue(cache.get("c").join().isPresent());
  }

  @Test
  public void getMakesAnEntryMostRecentlyUsed() {
    InMemoryPayloadCache cache = InMemoryPayloadCache.newBuilder().setMaxEntries(2).build();

    cache.set("a", payloadOfSize(1)).join();
    cache.set("b", payloadOfSize(1)).join();
    cache.get("a").join();
    cache.set("c", payloadOfSize(1)).join();

    assertTrue(cache.get("a").join().isPresent());
    assertEquals(Optional.empty(), cache.get("b").join());
  }

  @Test
  public void replacingAnEntryDoesNotCountItsOldSize() {
    Payload payload = payloadOfSize(100);
    InMemoryPayloadCache cache =
        InMemoryPayloadCache.newBuilder()
            .setMaxTotalBytes(2L * payload.getSerializedSize())
            .build();

    cache.set("a", payload).join();
    cache.set("a", payload).join();
    cache.set("b", payload).join();

    assertTrue(cache.get("a").join().isPresent());
    assertTrue(cache.get("b").join().isPresent());
  }

  @Test
  public void boundsEmptyPayloadsByTheEntryLimit() {
    InMemoryPayloadCache cache = InMemoryPayloadCache.newBuilder().setMaxEntries(1).build();
    Payload empty = Payload.getDefaultInstance();

    cache.set("a", empty).join();
    cache.set("b", empty).join();

    assertEquals(Optional.empty(), cache.get("a").join());
    assertTrue(cache.get("b").join().isPresent());
  }

  @Test
  public void rejectsNegativeLimits() {
    assertThrows(
        IllegalStateException.class,
        () -> InMemoryPayloadCache.newBuilder().setMaxTotalBytes(-1).build());
    assertThrows(
        IllegalStateException.class,
        () -> InMemoryPayloadCache.newBuilder().setMaxEntries(-1).build());
  }

  private static Payload payloadOfSize(int dataBytes) {
    return Payload.newBuilder().setData(ByteString.copyFrom(new byte[dataBytes])).build();
  }
}
