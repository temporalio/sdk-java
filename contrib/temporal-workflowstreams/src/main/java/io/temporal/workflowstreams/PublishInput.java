package io.temporal.workflowstreams;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.temporal.common.Experimental;
import java.util.ArrayList;
import java.util.List;

/**
 * The publish signal payload carrying a batch of entries, along with the dedup fields.
 *
 * <p>Field names are part of the cross-language wire protocol; this type must serialize to JSON
 * with exactly these names.
 */
@Experimental
public final class PublishInput {
  @JsonProperty("items")
  public List<PublishEntry> items = new ArrayList<>();

  @JsonProperty("publisher_id")
  public String publisherId = "";

  @JsonProperty("sequence")
  public long sequence;

  public PublishInput() {}

  public PublishInput(List<PublishEntry> items, String publisherId, long sequence) {
    this.items = items;
    this.publisherId = publisherId;
    this.sequence = sequence;
  }
}
