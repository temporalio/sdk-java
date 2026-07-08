package io.temporal.workflowstreams;

import io.temporal.common.Experimental;
import io.temporal.common.converter.PayloadConverter;

/** Options for constructing a {@link WorkflowStream}. */
@Experimental
public final class WorkflowStreamOptions {
  public static Builder newBuilder() {
    return new Builder();
  }

  public static WorkflowStreamOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final WorkflowStreamOptions DEFAULT_INSTANCE = newBuilder().build();

  private final PayloadConverter[] payloadConverters;

  private WorkflowStreamOptions(PayloadConverter[] payloadConverters) {
    this.payloadConverters = payloadConverters.clone();
  }

  public PayloadConverter[] getPayloadConverters() {
    return payloadConverters.clone();
  }

  public static final class Builder {
    private PayloadConverter[] payloadConverters = new PayloadConverter[0];

    private Builder() {}

    /**
     * Customizes how values published from workflow code (via {@link WorkflowTopicHandle#publish})
     * are serialized into per-item Payloads. They are combined into a {@link
     * io.temporal.common.converter.DefaultDataConverter} in the order given, so the last one should
     * be a catch-all such as a JSON converter.
     *
     * <p>As on the client side, only payload conversion happens here — never a payload codec. The
     * worker's codec chain runs once on the poll-update response that carries each batch to
     * subscribers, so encoding items here too would double-encode them; the {@code
     * PayloadConverter[]} type makes that impossible.
     *
     * <p>There is no public accessor for the worker's configured data converter inside workflow
     * code, so it cannot be picked up automatically; pass the matching payload converters here to
     * keep workflow-side publishes consistent with the rest of your workflow. Default: the standard
     * converter set.
     */
    public Builder setPayloadConverters(PayloadConverter... payloadConverters) {
      this.payloadConverters = payloadConverters;
      return this;
    }

    public WorkflowStreamOptions build() {
      return new WorkflowStreamOptions(payloadConverters);
    }
  }
}
