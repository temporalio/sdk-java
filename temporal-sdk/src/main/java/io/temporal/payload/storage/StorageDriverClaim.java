package io.temporal.payload.storage;

import io.temporal.common.Experimental;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Driver-defined reference to an externally stored payload, used to retrieve it later.
 *
 * @see StorageDriver
 */
@Experimental
public final class StorageDriverClaim {
  private final @Nonnull Map<String, String> claimData;

  public StorageDriverClaim(@Nonnull Map<String, String> claimData) {
    this.claimData =
        Collections.unmodifiableMap(new HashMap<>(Objects.requireNonNull(claimData, "claimData")));
  }

  @Nonnull
  public Map<String, String> getClaimData() {
    return claimData;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof StorageDriverClaim)) {
      return false;
    }
    return claimData.equals(((StorageDriverClaim) o).claimData);
  }

  @Override
  public int hashCode() {
    return claimData.hashCode();
  }
}
