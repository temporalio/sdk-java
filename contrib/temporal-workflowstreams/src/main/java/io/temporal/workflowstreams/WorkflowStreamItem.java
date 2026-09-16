package io.temporal.workflowstreams;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.Experimental;

/** A single item yielded by a subscription, including its typed value and raw {@link Payload}. */
@Experimental
public final class WorkflowStreamItem<T> {
  private final String topic;
  private final Payload payload;
  private final T value;
  private final long offset;

  /** Creates a raw item whose value is its payload. */
  @SuppressWarnings("unchecked")
  public WorkflowStreamItem(String topic, Payload payload, long offset) {
    this(topic, payload, (T) payload, offset);
  }

  /** Creates an item with both its raw payload and decoded value. */
  public WorkflowStreamItem(String topic, Payload payload, T value, long offset) {
    this.topic = topic;
    this.payload = payload;
    this.value = value;
    this.offset = offset;
  }

  public String getTopic() {
    return topic;
  }

  public Payload getPayload() {
    return payload;
  }

  public T getValue() {
    return value;
  }

  public long getOffset() {
    return offset;
  }
}
