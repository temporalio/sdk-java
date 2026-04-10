package io.temporal.client;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import io.temporal.api.enums.v1.ActivityIdConflictPolicy;
import io.temporal.api.enums.v1.ActivityIdReusePolicy;
import io.temporal.common.Experimental;
import io.temporal.common.Priority;
import io.temporal.common.RetryOptions;
import io.temporal.common.SearchAttributes;
import java.time.Duration;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Options for starting a standalone activity via {@link WorkflowClient#startActivity}.
 *
 * <p>At least one of {@link #getScheduleToCloseTimeout()} or {@link #getStartToCloseTimeout()} must
 * be set.
 */
@Experimental
public final class StartActivityOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(StartActivityOptions options) {
    return new Builder(options);
  }

  public static final class Builder {
    private String id;
    private String taskQueue;
    private @Nullable Duration scheduleToCloseTimeout;
    private @Nullable Duration scheduleToStartTimeout;
    private @Nullable Duration startToCloseTimeout;
    private @Nullable Duration heartbeatTimeout;
    private ActivityIdReusePolicy idReusePolicy =
        ActivityIdReusePolicy.ACTIVITY_ID_REUSE_POLICY_ALLOW_DUPLICATE;
    private ActivityIdConflictPolicy idConflictPolicy =
        ActivityIdConflictPolicy.ACTIVITY_ID_CONFLICT_POLICY_UNSPECIFIED;
    private @Nullable RetryOptions retryOptions;
    private @Nullable SearchAttributes typedSearchAttributes;
    private @Nullable String staticSummary;
    private @Nullable String staticDetails;
    private @Nullable Priority priority;

    private Builder() {}

    private Builder(StartActivityOptions options) {
      if (options == null) {
        return;
      }
      this.id = options.id;
      this.taskQueue = options.taskQueue;
      this.scheduleToCloseTimeout = options.scheduleToCloseTimeout;
      this.scheduleToStartTimeout = options.scheduleToStartTimeout;
      this.startToCloseTimeout = options.startToCloseTimeout;
      this.heartbeatTimeout = options.heartbeatTimeout;
      this.idReusePolicy = options.idReusePolicy;
      this.idConflictPolicy = options.idConflictPolicy;
      this.retryOptions = options.retryOptions;
      this.typedSearchAttributes = options.typedSearchAttributes;
      this.staticSummary = options.staticSummary;
      this.staticDetails = options.staticDetails;
      this.priority = options.priority;
    }

    /** Required. A unique identifier for this activity in the namespace. */
    public Builder setId(String id) {
      this.id = id;
      return this;
    }

    /** Required. The task queue that workers will poll for this activity. */
    public Builder setTaskQueue(String taskQueue) {
      this.taskQueue = taskQueue;
      return this;
    }

    /**
     * Total time the caller is willing to wait for the activity to complete (including all
     * retries). Either this or {@link #setStartToCloseTimeout(Duration)} is required.
     */
    public Builder setScheduleToCloseTimeout(Duration scheduleToCloseTimeout) {
      this.scheduleToCloseTimeout = scheduleToCloseTimeout;
      return this;
    }

    /**
     * Time the activity task may wait in the task queue before a worker picks it up. Non-retryable.
     */
    public Builder setScheduleToStartTimeout(Duration scheduleToStartTimeout) {
      this.scheduleToStartTimeout = scheduleToStartTimeout;
      return this;
    }

    /**
     * Maximum time for a single attempt. Either this or {@link
     * #setScheduleToCloseTimeout(Duration)} is required.
     */
    public Builder setStartToCloseTimeout(Duration startToCloseTimeout) {
      this.startToCloseTimeout = startToCloseTimeout;
      return this;
    }

    /** Maximum time between heartbeats before the server considers the activity failed. */
    public Builder setHeartbeatTimeout(Duration heartbeatTimeout) {
      this.heartbeatTimeout = heartbeatTimeout;
      return this;
    }

    /**
     * Controls behavior when an activity with the same ID was previously run and is now closed.
     * Defaults to {@code ALLOW_DUPLICATE}.
     */
    public Builder setIdReusePolicy(ActivityIdReusePolicy idReusePolicy) {
      this.idReusePolicy = Objects.requireNonNull(idReusePolicy);
      return this;
    }

    /**
     * Controls behavior when an activity with the same ID is currently running. Defaults to {@code
     * UNSPECIFIED} which maps to the server default (fail).
     */
    public Builder setIdConflictPolicy(ActivityIdConflictPolicy idConflictPolicy) {
      this.idConflictPolicy = Objects.requireNonNull(idConflictPolicy);
      return this;
    }

    /** Retry policy. If not set the server default is used. */
    public Builder setRetryOptions(RetryOptions retryOptions) {
      this.retryOptions = retryOptions;
      return this;
    }

    /** Typed search attributes to attach to this activity execution. */
    public Builder setTypedSearchAttributes(SearchAttributes typedSearchAttributes) {
      this.typedSearchAttributes = typedSearchAttributes;
      return this;
    }

    /** Short static summary for UI display; encoded as a payload in UserMetadata. */
    public Builder setStaticSummary(String staticSummary) {
      this.staticSummary = staticSummary;
      return this;
    }

    /** Longer static details for UI display; encoded as a payload in UserMetadata. */
    public Builder setStaticDetails(String staticDetails) {
      this.staticDetails = staticDetails;
      return this;
    }

    /** Priority hint for task routing. */
    public Builder setPriority(Priority priority) {
      this.priority = priority;
      return this;
    }

    public StartActivityOptions build() {
      Preconditions.checkArgument(!Strings.isNullOrEmpty(id), "id must not be null or empty");
      Preconditions.checkArgument(
          !Strings.isNullOrEmpty(taskQueue), "taskQueue must not be null or empty");
      Preconditions.checkArgument(
          scheduleToCloseTimeout != null || startToCloseTimeout != null,
          "At least one of scheduleToCloseTimeout or startToCloseTimeout must be set");
      return new StartActivityOptions(this);
    }
  }

  private final String id;
  private final String taskQueue;
  private final @Nullable Duration scheduleToCloseTimeout;
  private final @Nullable Duration scheduleToStartTimeout;
  private final @Nullable Duration startToCloseTimeout;
  private final @Nullable Duration heartbeatTimeout;
  private final ActivityIdReusePolicy idReusePolicy;
  private final ActivityIdConflictPolicy idConflictPolicy;
  private final @Nullable RetryOptions retryOptions;
  private final @Nullable SearchAttributes typedSearchAttributes;
  private final @Nullable String staticSummary;
  private final @Nullable String staticDetails;
  private final @Nullable Priority priority;

  private StartActivityOptions(Builder builder) {
    this.id = builder.id;
    this.taskQueue = builder.taskQueue;
    this.scheduleToCloseTimeout = builder.scheduleToCloseTimeout;
    this.scheduleToStartTimeout = builder.scheduleToStartTimeout;
    this.startToCloseTimeout = builder.startToCloseTimeout;
    this.heartbeatTimeout = builder.heartbeatTimeout;
    this.idReusePolicy = builder.idReusePolicy;
    this.idConflictPolicy = builder.idConflictPolicy;
    this.retryOptions = builder.retryOptions;
    this.typedSearchAttributes = builder.typedSearchAttributes;
    this.staticSummary = builder.staticSummary;
    this.staticDetails = builder.staticDetails;
    this.priority = builder.priority;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  public String getId() {
    return id;
  }

  public String getTaskQueue() {
    return taskQueue;
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
  public Duration getHeartbeatTimeout() {
    return heartbeatTimeout;
  }

  public ActivityIdReusePolicy getIdReusePolicy() {
    return idReusePolicy;
  }

  public ActivityIdConflictPolicy getIdConflictPolicy() {
    return idConflictPolicy;
  }

  @Nullable
  public RetryOptions getRetryOptions() {
    return retryOptions;
  }

  @Nullable
  public SearchAttributes getTypedSearchAttributes() {
    return typedSearchAttributes;
  }

  @Nullable
  public String getStaticSummary() {
    return staticSummary;
  }

  @Nullable
  public String getStaticDetails() {
    return staticDetails;
  }

  @Nullable
  public Priority getPriority() {
    return priority;
  }

  @Override
  public String toString() {
    return "StartActivityOptions{"
        + "id='"
        + id
        + "', taskQueue='"
        + taskQueue
        + "', scheduleToCloseTimeout="
        + scheduleToCloseTimeout
        + ", scheduleToStartTimeout="
        + scheduleToStartTimeout
        + ", startToCloseTimeout="
        + startToCloseTimeout
        + ", heartbeatTimeout="
        + heartbeatTimeout
        + ", idReusePolicy="
        + idReusePolicy
        + ", idConflictPolicy="
        + idConflictPolicy
        + ", retryOptions="
        + retryOptions
        + ", typedSearchAttributes="
        + typedSearchAttributes
        + ", staticSummary='"
        + staticSummary
        + "', staticDetails='"
        + staticDetails
        + "', priority="
        + priority
        + '}';
  }
}
