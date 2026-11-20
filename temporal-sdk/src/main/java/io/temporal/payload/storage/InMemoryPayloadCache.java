package io.temporal.payload.storage;

import com.google.common.base.Preconditions;
import com.google.protobuf.InvalidProtocolBufferException;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.Experimental;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;

/**
 * In-memory {@link PayloadCache} that evicts the least recently used entries to stay within an
 * entry count and a total byte size. An entry's size is the serialized size of its payload.
 */
@Experimental
@ThreadSafe
public final class InMemoryPayloadCache implements PayloadCache {
  public static final long DEFAULT_MAX_TOTAL_BYTES = 64L * 1024 * 1024;
  public static final int DEFAULT_MAX_ENTRIES = 10_000;

  public static Builder newBuilder() {
    return new Builder();
  }

  private final long maxTotalBytes;
  private final int maxEntries;

  /** Iterates from least to most recently used. Guarded by {@code this}. */
  private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

  /** Guarded by {@code this}. */
  private long totalBytes;

  /** Creates a cache with the default limits. */
  public InMemoryPayloadCache() {
    this(DEFAULT_MAX_TOTAL_BYTES, DEFAULT_MAX_ENTRIES);
  }

  private InMemoryPayloadCache(long maxTotalBytes, int maxEntries) {
    this.maxTotalBytes = maxTotalBytes;
    this.maxEntries = maxEntries;
  }

  public long getMaxTotalBytes() {
    return maxTotalBytes;
  }

  public int getMaxEntries() {
    return maxEntries;
  }

  @Nonnull
  @Override
  public CompletableFuture<Optional<Payload>> get(@Nonnull String key) {
    Entry entry;
    synchronized (this) {
      entry = entries.get(key);
    }
    return CompletableFuture.completedFuture(
        entry == null ? Optional.empty() : Optional.of(entry.payload));
  }

  /**
   * Declines a payload larger than the total byte limit, and every payload when the entry limit is
   * zero. Stores a copy of the payload that does not share memory with the caller's payload, so the
   * cache never holds a larger buffer the payload was parsed from.
   */
  @Nonnull
  @Override
  public CompletableFuture<Boolean> set(@Nonnull String key, @Nonnull Payload payload) {
    int size = payload.getSerializedSize();
    if (size > maxTotalBytes || maxEntries == 0) {
      return CompletableFuture.completedFuture(false);
    }
    Payload copy;
    try {
      copy = Payload.parseFrom(payload.toByteArray());
    } catch (InvalidProtocolBufferException e) {
      CompletableFuture<Boolean> failed = new CompletableFuture<>();
      failed.completeExceptionally(e);
      return failed;
    }
    synchronized (this) {
      Entry existing = entries.remove(key);
      if (existing != null) {
        totalBytes -= existing.size;
      }
      Iterator<Entry> leastRecentlyUsed = entries.values().iterator();
      while (leastRecentlyUsed.hasNext()
          && (entries.size() >= maxEntries || totalBytes + size > maxTotalBytes)) {
        totalBytes -= leastRecentlyUsed.next().size;
        leastRecentlyUsed.remove();
      }
      entries.put(key, new Entry(copy, size));
      totalBytes += size;
    }
    return CompletableFuture.completedFuture(true);
  }

  private static final class Entry {
    final Payload payload;
    final int size;

    Entry(Payload payload, int size) {
      this.payload = payload;
      this.size = size;
    }
  }

  public static final class Builder {
    private long maxTotalBytes = DEFAULT_MAX_TOTAL_BYTES;
    private int maxEntries = DEFAULT_MAX_ENTRIES;

    private Builder() {}

    /**
     * Total serialized size of all cached payloads, in bytes. Must not be negative. Defaults to 64
     * MiB.
     */
    public Builder setMaxTotalBytes(long maxTotalBytes) {
      this.maxTotalBytes = maxTotalBytes;
      return this;
    }

    /** Maximum number of cached payloads. Must not be negative. Defaults to 10,000. */
    public Builder setMaxEntries(int maxEntries) {
      this.maxEntries = maxEntries;
      return this;
    }

    public InMemoryPayloadCache build() {
      Preconditions.checkState(
          maxTotalBytes >= 0, "maxTotalBytes must be greater than or equal to zero");
      Preconditions.checkState(maxEntries >= 0, "maxEntries must be greater than or equal to zero");
      return new InMemoryPayloadCache(maxTotalBytes, maxEntries);
    }
  }
}
