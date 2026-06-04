package io.temporal.payload.storage;

import io.temporal.common.Experimental;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Identity of the activity a payload is being stored on behalf of. Provided to a {@link
 * StorageDriver} via {@link StorageDriverStoreContext#getTarget()}. All fields except {@code
 * namespace} are best-effort and may be {@code null} when not available at store time.
 */
@Experimental
public final class StorageDriverActivityInfo implements StorageDriverTargetInfo {
  private final @Nonnull String namespace;
  private final @Nullable String id;
  private final @Nullable String runId;
  private final @Nullable String type;

  public StorageDriverActivityInfo(
      @Nonnull String namespace,
      @Nullable String id,
      @Nullable String runId,
      @Nullable String type) {
    this.namespace = Objects.requireNonNull(namespace, "namespace");
    this.id = id;
    this.runId = runId;
    this.type = type;
  }

  @Nonnull
  public String getNamespace() {
    return namespace;
  }

  @Nullable
  public String getId() {
    return id;
  }

  @Nullable
  public String getRunId() {
    return runId;
  }

  @Nullable
  public String getType() {
    return type;
  }
}
