package io.temporal.client;

import io.temporal.api.enums.v1.NexusOperationIdConflictPolicy;
import io.temporal.api.enums.v1.NexusOperationIdReusePolicy;
import io.temporal.common.Experimental;
import io.temporal.common.SearchAttributes;
import java.time.Duration;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Per-call options for starting a standalone Nexus operation via {@link
 * UntypedNexusServiceClient#start} (or its typed counterpart).
 */
@Experimental
public final class StartNexusOperationOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(StartNexusOperationOptions options) {
    return new Builder(options);
  }

  private static final StartNexusOperationOptions DEFAULT_INSTANCE = newBuilder().build();

  /** Returns an options instance with no per-call fields set. */
  public static StartNexusOperationOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  public static final class Builder {
    private @Nullable String id;
    private @Nullable Duration scheduleToCloseTimeout;
    private @Nullable Duration scheduleToStartTimeout;
    private @Nullable Duration startToCloseTimeout;
    private @Nullable SearchAttributes typedSearchAttributes;
    private @Nullable String summary;
    private @Nullable NexusOperationIdReusePolicy idReusePolicy;
    private @Nullable NexusOperationIdConflictPolicy idConflictPolicy;

    private Builder() {}

    private Builder(StartNexusOperationOptions options) {
      if (options == null) {
        return;
      }
      this.id = options.id;
      this.scheduleToCloseTimeout = options.scheduleToCloseTimeout;
      this.scheduleToStartTimeout = options.scheduleToStartTimeout;
      this.startToCloseTimeout = options.startToCloseTimeout;
      this.typedSearchAttributes = options.typedSearchAttributes;
      this.summary = options.summary;
      this.idReusePolicy = options.idReusePolicy;
      this.idConflictPolicy = options.idConflictPolicy;
    }

    /**
     * Required. Unique identifier for this operation within its namespace. If left null, the SDK
     * generates a random UUID.
     */
    public Builder setId(@Nullable String id) {
      this.id = id;
      return this;
    }

    /** Total time the caller is willing to wait for the operation to complete. */
    public Builder setScheduleToCloseTimeout(@Nullable Duration scheduleToCloseTimeout) {
      this.scheduleToCloseTimeout = scheduleToCloseTimeout;
      return this;
    }

    /** Time the operation may wait in the queue before a handler picks it up. */
    public Builder setScheduleToStartTimeout(@Nullable Duration scheduleToStartTimeout) {
      this.scheduleToStartTimeout = scheduleToStartTimeout;
      return this;
    }

    /** Maximum time for a single attempt. */
    public Builder setStartToCloseTimeout(@Nullable Duration startToCloseTimeout) {
      this.startToCloseTimeout = startToCloseTimeout;
      return this;
    }

    /** Typed search attributes to attach to this operation execution. */
    public Builder setTypedSearchAttributes(@Nullable SearchAttributes typedSearchAttributes) {
      this.typedSearchAttributes = typedSearchAttributes;
      return this;
    }

    /** Short summary for UI display. */
    public Builder setSummary(@Nullable String summary) {
      this.summary = summary;
      return this;
    }

    /** Controls behavior when an operation with the same ID was previously run and is closed. */
    public Builder setIdReusePolicy(@Nullable NexusOperationIdReusePolicy idReusePolicy) {
      this.idReusePolicy = idReusePolicy;
      return this;
    }

    /** Controls behavior when an operation with the same ID is currently running. */
    public Builder setIdConflictPolicy(@Nullable NexusOperationIdConflictPolicy idConflictPolicy) {
      this.idConflictPolicy = idConflictPolicy;
      return this;
    }

    public StartNexusOperationOptions build() {
      return new StartNexusOperationOptions(this);
    }
  }

  private final @Nullable String id;
  private final @Nullable Duration scheduleToCloseTimeout;
  private final @Nullable Duration scheduleToStartTimeout;
  private final @Nullable Duration startToCloseTimeout;
  private final @Nullable SearchAttributes typedSearchAttributes;
  private final @Nullable String summary;
  private final @Nullable NexusOperationIdReusePolicy idReusePolicy;
  private final @Nullable NexusOperationIdConflictPolicy idConflictPolicy;

  private StartNexusOperationOptions(Builder builder) {
    this.id = builder.id;
    this.scheduleToCloseTimeout = builder.scheduleToCloseTimeout;
    this.scheduleToStartTimeout = builder.scheduleToStartTimeout;
    this.startToCloseTimeout = builder.startToCloseTimeout;
    this.typedSearchAttributes = builder.typedSearchAttributes;
    this.summary = builder.summary;
    this.idReusePolicy = builder.idReusePolicy;
    this.idConflictPolicy = builder.idConflictPolicy;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Nullable
  public String getId() {
    return id;
  }

  @Nullable
  public Duration getScheduleToCloseTimeout() {
    return scheduleToCloseTimeout;
  }

  @Nullable
  public Duration getScheduleToStartTimeout() {
    return scheduleToStartTimeout;
  }

  @Nullable
  public Duration getStartToCloseTimeout() {
    return startToCloseTimeout;
  }

  @Nullable
  public SearchAttributes getTypedSearchAttributes() {
    return typedSearchAttributes;
  }

  @Nullable
  public String getSummary() {
    return summary;
  }

  @Nullable
  public NexusOperationIdReusePolicy getIdReusePolicy() {
    return idReusePolicy;
  }

  @Nullable
  public NexusOperationIdConflictPolicy getIdConflictPolicy() {
    return idConflictPolicy;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    StartNexusOperationOptions that = (StartNexusOperationOptions) o;
    return Objects.equals(id, that.id)
        && Objects.equals(scheduleToCloseTimeout, that.scheduleToCloseTimeout)
        && Objects.equals(scheduleToStartTimeout, that.scheduleToStartTimeout)
        && Objects.equals(startToCloseTimeout, that.startToCloseTimeout)
        && Objects.equals(typedSearchAttributes, that.typedSearchAttributes)
        && Objects.equals(summary, that.summary)
        && idReusePolicy == that.idReusePolicy
        && idConflictPolicy == that.idConflictPolicy;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        id,
        scheduleToCloseTimeout,
        scheduleToStartTimeout,
        startToCloseTimeout,
        typedSearchAttributes,
        summary,
        idReusePolicy,
        idConflictPolicy);
  }

  @Override
  public String toString() {
    return "StartNexusOperationOptions{"
        + "id='"
        + id
        + "', scheduleToCloseTimeout="
        + scheduleToCloseTimeout
        + ", scheduleToStartTimeout="
        + scheduleToStartTimeout
        + ", startToCloseTimeout="
        + startToCloseTimeout
        + ", typedSearchAttributes="
        + typedSearchAttributes
        + ", summary='"
        + summary
        + "', idReusePolicy="
        + idReusePolicy
        + ", idConflictPolicy="
        + idConflictPolicy
        + '}';
  }
}
