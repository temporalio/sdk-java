package io.temporal.workflowstreams;

import io.temporal.common.Experimental;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Options for {@link WorkflowStreamClient#subscribe}. */
@Experimental
public final class SubscribeOptions {
  public static Builder newBuilder() {
    return new Builder();
  }

  public static SubscribeOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final SubscribeOptions DEFAULT_INSTANCE = newBuilder().build();

  private final List<String> topics;
  private final long fromOffset;
  private final Duration pollCooldown;

  private SubscribeOptions(List<String> topics, long fromOffset, Duration pollCooldown) {
    this.topics = topics;
    this.fromOffset = fromOffset;
    this.pollCooldown = pollCooldown;
  }

  public List<String> getTopics() {
    return topics;
  }

  public long getFromOffset() {
    return fromOffset;
  }

  public Duration getPollCooldown() {
    return pollCooldown;
  }

  public static final class Builder {
    private List<String> topics = new ArrayList<>();
    private long fromOffset;
    private Duration pollCooldown = WorkflowStreamConstants.DEFAULT_POLL_COOLDOWN;

    private Builder() {}

    /** Filters the subscription. Empty (the default) means all topics. */
    public Builder setTopics(String... topics) {
      this.topics = new ArrayList<>(Arrays.asList(topics));
      return this;
    }

    /** Global offset to start from. Zero (the default) means the beginning. */
    public Builder setFromOffset(long fromOffset) {
      this.fromOffset = fromOffset;
      return this;
    }

    /**
     * Minimum interval between polls when no more items are immediately ready. Default: 100
     * milliseconds.
     */
    public Builder setPollCooldown(Duration pollCooldown) {
      this.pollCooldown = pollCooldown;
      return this;
    }

    public SubscribeOptions build() {
      return new SubscribeOptions(topics, fromOffset, pollCooldown);
    }
  }
}
