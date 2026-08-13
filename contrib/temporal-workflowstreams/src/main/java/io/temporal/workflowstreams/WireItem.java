package io.temporal.workflowstreams;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.temporal.common.Experimental;

/**
 * The wire representation of a stream item. {@code data} is a base64-encoded, serialized {@link
 * io.temporal.api.common.v1.Payload}.
 *
 * <p>Field names are part of the cross-language wire protocol; this type must serialize to JSON
 * with exactly these names.
 */
@Experimental
public final class WireItem {
  @JsonProperty("topic")
  public String topic;

  @JsonProperty("data")
  public String data;

  @JsonProperty("offset")
  public long offset;

  public WireItem() {}

  public WireItem(String topic, String data, long offset) {
    this.topic = topic;
    this.data = data;
    this.offset = offset;
  }
}
