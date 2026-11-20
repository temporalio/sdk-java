package io.temporal.internal.payload.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import io.temporal.payload.storage.StorageDriverClaim;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class PayloadCacheKeysTest {

  @Test
  public void matchesTheDocumentedCrossSdkExample() {
    Map<String, String> claimData = new LinkedHashMap<>();
    claimData.put("bucket", "abc");
    claimData.put("key", "1");

    assertEquals(
        "v0/temporal/extstore/6:aws-s36:bucket3:abc3:key1:1",
        PayloadCacheKeys.forClaim("aws-s3", new StorageDriverClaim(claimData)));
  }

  /**
   * U+FF61 sorts before U+1F600 by UTF-8 bytes (EF before F0) but after it by UTF-16 units (FF61
   * after D83D). The expected value was produced by the TypeScript SDK.
   */
  @Test
  public void ordersClaimKeysByUtf8BytesAndPrefixesUtf8Lengths() {
    Map<String, String> claimData = new HashMap<>();
    claimData.put("😀", "z");
    claimData.put("｡", "y");
    claimData.put("a", "1");
    claimData.put("é", "x");

    assertEquals(
        "v0/temporal/extstore/2:s31:a1:12:é1:x3:｡1:y4:😀1:z",
        PayloadCacheKeys.forClaim("s3", new StorageDriverClaim(claimData)));
  }

  @Test
  public void doesNotDependOnClaimInsertionOrder() {
    Map<String, String> first = new LinkedHashMap<>();
    first.put("bucket", "abc");
    first.put("key", "1");
    Map<String, String> reordered = new LinkedHashMap<>();
    reordered.put("key", "1");
    reordered.put("bucket", "abc");

    assertEquals(
        PayloadCacheKeys.forClaim("s3", new StorageDriverClaim(first)),
        PayloadCacheKeys.forClaim("s3", new StorageDriverClaim(reordered)));
  }

  @Test
  public void lengthPrefixesKeepDifferentClaimsApart() {
    Map<String, String> joined = new HashMap<>();
    joined.put("a", "1:b1:2");
    Map<String, String> separate = new HashMap<>();
    separate.put("a", "1");
    separate.put("b", "2");

    assertNotEquals(
        PayloadCacheKeys.forClaim("s3", new StorageDriverClaim(joined)),
        PayloadCacheKeys.forClaim("s3", new StorageDriverClaim(separate)));
  }
}
