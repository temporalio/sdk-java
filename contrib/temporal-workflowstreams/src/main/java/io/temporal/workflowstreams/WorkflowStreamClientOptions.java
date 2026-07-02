package io.temporal.workflowstreams;

import io.temporal.common.Experimental;
import io.temporal.common.converter.PayloadConverter;
import java.time.Duration;

/** Options for constructing a {@link WorkflowStreamClient}. */
@Experimental
public final class WorkflowStreamClientOptions {
  public static Builder newBuilder() {
    return new Builder();
  }

  public static WorkflowStreamClientOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final WorkflowStreamClientOptions DEFAULT_INSTANCE = newBuilder().build();

  private final Duration batchInterval;
  private final int maxBatchSize;
  private final Duration maxRetryDuration;
  private final PayloadConverter[] payloadConverters;

  private WorkflowStreamClientOptions(
      Duration batchInterval,
      int maxBatchSize,
      Duration maxRetryDuration,
      PayloadConverter[] payloadConverters) {
    this.batchInterval = batchInterval;
    this.maxBatchSize = maxBatchSize;
    this.maxRetryDuration = maxRetryDuration;
    this.payloadConverters = payloadConverters;
  }

  public Duration getBatchInterval() {
    return batchInterval;
  }

  public int getMaxBatchSize() {
    return maxBatchSize;
  }

  public Duration getMaxRetryDuration() {
    return maxRetryDuration;
  }

  public PayloadConverter[] getPayloadConverters() {
    return payloadConverters;
  }

  public static final class Builder {
    private Duration batchInterval = WorkflowStreamConstants.DEFAULT_BATCH_INTERVAL;
    private int maxBatchSize;
    private Duration maxRetryDuration = WorkflowStreamConstants.DEFAULT_MAX_RETRY_DURATION;
    private PayloadConverter[] payloadConverters = new PayloadConverter[0];

    private Builder() {}

    /** Interval between automatic flushes. Default: 2 seconds. */
    public Builder setBatchInterval(Duration batchInterval) {
      this.batchInterval = batchInterval;
      return this;
    }

    /**
     * Triggers a flush once the buffer reaches this many items. Zero (the default) disables
     * size-based flushing.
     */
    public Builder setMaxBatchSize(int maxBatchSize) {
      this.maxBatchSize = maxBatchSize;
      return this;
    }

    /**
     * Maximum time to retry a failed flush before surfacing a {@link FlushTimeoutException}. Must
     * be less than the workflow's publisher TTL (default 15 minutes) to preserve exactly-once
     * delivery. Default: 10 minutes.
     */
    public Builder setMaxRetryDuration(Duration maxRetryDuration) {
      this.maxRetryDuration = maxRetryDuration;
      return this;
    }

    /**
     * Customizes how published values are serialized into the per-item Payloads carried inside each
     * batch. They are combined into a {@link io.temporal.common.converter.DefaultDataConverter} in
     * the order given, so the last one should be a catch-all such as a JSON converter.
     *
     * <p>Only payload conversion happens here — never a payload codec (encryption, compression).
     * The codec chain configured on the Temporal client runs once on the signal/update envelope
     * that carries each batch, so encoding items here too would double-encode them; the {@code
     * PayloadConverter[]} type makes that mistake impossible. To decode subscribed items, use a
     * converter built from the same payload converters.
     *
     * <p>Default: the standard converter set.
     */
    public Builder setPayloadConverters(PayloadConverter... payloadConverters) {
      this.payloadConverters = payloadConverters;
      return this;
    }

    public WorkflowStreamClientOptions build() {
      return new WorkflowStreamClientOptions(
          batchInterval, maxBatchSize, maxRetryDuration, payloadConverters);
    }
  }
}
