package io.temporal.payload.storage;

import io.temporal.common.Experimental;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.annotation.Nonnull;

/** Hash helpers shared by external storage drivers. */
@Experimental
public final class PayloadStorageHashes {
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private PayloadStorageHashes() {}

  /** Returns the lower-case SHA-256 hex digest of {@code data}. */
  @Nonnull
  public static String sha256Hex(@Nonnull byte[] data) {
    byte[] digest;
    try {
      // If we ever move to Java 17+ we can use HexFormat.of().formatHex() instead.
      digest = MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 MessageDigest cannot be found", e);
    }
    StringBuilder sb = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
    }
    return sb.toString();
  }
}
