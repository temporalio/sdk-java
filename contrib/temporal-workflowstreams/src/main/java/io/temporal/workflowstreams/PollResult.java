package io.temporal.workflowstreams;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.temporal.common.Experimental;
import java.util.ArrayList;
import java.util.List;

/**
 * The poll update response: items matching the poll request. When {@code more_ready} is true the
 * response was truncated to stay within size limits and the subscriber should poll again
 * immediately rather than applying a cooldown.
 *
 * <p>Field names are part of the cross-language wire protocol; this type must serialize to JSON
 * with exactly these names.
 */
@Experimental
public final class PollResult {
  @JsonProperty("items")
  public List<WireItem> items = new ArrayList<>();

  @JsonProperty("next_offset")
  public long nextOffset;

  @JsonProperty("more_ready")
  public boolean moreReady;

  public PollResult() {}

  public PollResult(List<WireItem> items, long nextOffset, boolean moreReady) {
    this.items = items;
    this.nextOffset = nextOffset;
    this.moreReady = moreReady;
  }
}
