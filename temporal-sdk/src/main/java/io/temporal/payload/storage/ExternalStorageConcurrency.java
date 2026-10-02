package io.temporal.payload.storage;

import com.google.common.base.Preconditions;
import io.temporal.common.Experimental;

/** Configures concurrency limits for external storage operations. */
@Experimental
public final class ExternalStorageConcurrency {
  static final int DEFAULT_MAX_DRIVER_OPERATIONS = 64;
  static final int DEFAULT_MAX_OPERATIONS_PER_MESSAGE = 8;

  private static final ExternalStorageConcurrency DEFAULT_INSTANCE = newBuilder().build();

  public static Builder newBuilder() {
    return new Builder();
  }

  /** Returns the defaults, 64 and 8. */
  public static ExternalStorageConcurrency getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private final int maxDriverOperations;
  private final int maxOperationsPerMessage;

  private ExternalStorageConcurrency(int maxDriverOperations, int maxOperationsPerMessage) {
    this.maxDriverOperations = maxDriverOperations;
    this.maxOperationsPerMessage = maxOperationsPerMessage;
  }

  /**
   * The maximum number of concurrent external storage operations that drivers can execute for a
   * single {@link ExternalStorage}. All drivers on that instance share this limit. Defaults to 64.
   */
  public int getMaxDriverOperations() {
    return maxDriverOperations;
  }

  /**
   * The maximum number of concurrent external storage operations that drivers can execute for a
   * single message (a message is input or output such as a workflow activation, completion, or
   * client request). This prevents one message from monopolizing resources. Defaults to 8.
   */
  public int getMaxOperationsPerMessage() {
    return maxOperationsPerMessage;
  }

  public static final class Builder {
    private int maxDriverOperations = DEFAULT_MAX_DRIVER_OPERATIONS;
    private int maxOperationsPerMessage = DEFAULT_MAX_OPERATIONS_PER_MESSAGE;

    private Builder() {}

    /** Must be at least 1. Defaults to 64. */
    public Builder setMaxDriverOperations(int maxDriverOperations) {
      this.maxDriverOperations = maxDriverOperations;
      return this;
    }

    /** Must be at least 1. Defaults to 8. */
    public Builder setMaxOperationsPerMessage(int maxOperationsPerMessage) {
      this.maxOperationsPerMessage = maxOperationsPerMessage;
      return this;
    }

    /** Throws if either limit is below 1. */
    public ExternalStorageConcurrency build() {
      Preconditions.checkState(maxDriverOperations >= 1, "maxDriverOperations must be at least 1");
      Preconditions.checkState(
          maxOperationsPerMessage >= 1, "maxOperationsPerMessage must be at least 1");
      return new ExternalStorageConcurrency(maxDriverOperations, maxOperationsPerMessage);
    }
  }
}
