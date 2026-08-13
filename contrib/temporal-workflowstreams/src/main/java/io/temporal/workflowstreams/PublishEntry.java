package io.temporal.workflowstreams;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.temporal.common.Experimental;

/**
 * A single entry within a publish batch on the wire. {@code data} is a base64-encoded, serialized
 * {@link io.temporal.api.common.v1.Payload}.
 *
 * <p>Field names are part of the cross-language wire protocol; this type must serialize to JSON
 * with exactly these names.
 */
@Experimental
public final class PublishEntry {
  @JsonProperty("topic")
  public String topic;

  @JsonProperty("data")
  public String data;

  public PublishEntry() {}

  public PublishEntry(String topic, String data) {
    this.topic = topic;
    this.data = data;
  }
}
