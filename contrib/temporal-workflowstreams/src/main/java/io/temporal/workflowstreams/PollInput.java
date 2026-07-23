package io.temporal.workflowstreams;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.temporal.common.Experimental;
import java.util.ArrayList;
import java.util.List;

/**
 * The poll update payload: a request to long-poll for new items.
 *
 * <p>Field names are part of the cross-language wire protocol; this type must serialize to JSON
 * with exactly these names.
 */
@Experimental
public final class PollInput {
  /** Topics to filter on. Empty means all topics. */
  @JsonProperty("topics")
  public List<String> topics = new ArrayList<>();

  /** Global offset to start from. Zero means the beginning. */
  @JsonProperty("from_offset")
  public long fromOffset;

  public PollInput() {}

  public PollInput(List<String> topics, long fromOffset) {
    this.topics = topics;
    this.fromOffset = fromOffset;
  }
}
