package io.temporal.internal.payload.storage;

import com.google.common.primitives.UnsignedBytes;
import io.temporal.payload.storage.StorageDriverClaim;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Builds {@link io.temporal.payload.storage.PayloadCache} keys for external storage claims. The
 * format is shared by all Temporal SDKs, so it must stay byte-for-byte identical across them.
 */
final class PayloadCacheKeys {
  private static final String PREFIX = "v0/temporal/extstore/";

  private static final Comparator<byte[]> UTF8_BYTE_ORDER =
      UnsignedBytes.lexicographicalComparator();

  /**
   * Returns {@code PREFIX + lengthPrefixed(driverName)} followed by {@code lengthPrefixed(key) +
   * lengthPrefixed(value)} for each claim entry, ordered by the UTF-8 bytes of the key. {@code
   * lengthPrefixed(s)} is the UTF-8 byte length of {@code s}, a colon, and {@code s}.
   */
  static String forClaim(String driverName, StorageDriverClaim claim) {
    List<Map.Entry<String, String>> claimEntries = new ArrayList<>(claim.getClaimData().entrySet());
    // Other SDKs order by UTF-8 bytes. String.compareTo orders by UTF-16 units, which differs for
    // characters outside the Basic Multilingual Plane.
    claimEntries.sort(
        (left, right) -> UTF8_BYTE_ORDER.compare(utf8(left.getKey()), utf8(right.getKey())));
    StringBuilder key = new StringBuilder(PREFIX);
    appendLengthPrefixed(key, driverName);
    for (Map.Entry<String, String> claimEntry : claimEntries) {
      appendLengthPrefixed(key, claimEntry.getKey());
      appendLengthPrefixed(key, claimEntry.getValue());
    }
    return key.toString();
  }

  private static void appendLengthPrefixed(StringBuilder key, String value) {
    key.append(utf8(value).length).append(':').append(value);
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private PayloadCacheKeys() {}
}
