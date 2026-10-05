package io.temporal.workflowstreams;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.Experimental;

/**
 * A single decoded item yielded by a subscription. {@code payload} is the raw {@link Payload};
 * decode it at the call site with a payload converter, e.g. {@code
 * DefaultDataConverter.STANDARD_INSTANCE.fromPayload(item.getPayload(), String.class,
 * String.class)}.
 */
@Experimental
public final class WorkflowStreamItem {
  private final String topic;
  private final Payload payload;
  private final long offset;

  public WorkflowStreamItem(String topic, Payload payload, long offset) {
    this.topic = topic;
    this.payload = payload;
    this.offset = offset;
  }

  public String getTopic() {
    return topic;
  }

  public Payload getPayload() {
    return payload;
  }

  public long getOffset() {
    return offset;
  }
}
