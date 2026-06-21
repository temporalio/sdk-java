package io.temporal.common.converter;

import io.temporal.common.Experimental;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * A claim reference token that replaces large payloads in Event History when using external
 * storage. The claim contains an opaque map of key-value pairs that the {@link StorageDriver} uses
 * to locate the stored payload. The SDK persists this in workflow history and does not interpret
 * the contents.
 *
 * <p>For example, an S3 driver might store {@code {"key": "<sha256-hash>"}} in the claim data.
 */
@Experimental
public final class StorageDriverClaim {

  private final Map<String, String> claimData;

  /**
   * Creates a new claim with the given claim data.
   *
   * @param claimData opaque map of key-value pairs used by the driver to locate the payload
   */
  public StorageDriverClaim(Map<String, String> claimData) {
    this.claimData = Collections.unmodifiableMap(Objects.requireNonNull(claimData, "claimData"));
  }

  /**
   * Returns the opaque map of key-value pairs that the driver uses to locate the stored payload.
   *
   * @return unmodifiable map of claim data
   */
  public Map<String, String> getClaimData() {
    return claimData;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    StorageDriverClaim that = (StorageDriverClaim) o;
    return Objects.equals(claimData, that.claimData);
  }

  @Override
  public int hashCode() {
    return Objects.hash(claimData);
  }

  @Override
  public String toString() {
    return "StorageDriverClaim{" + "claimData=" + claimData + '}';
  }
}
