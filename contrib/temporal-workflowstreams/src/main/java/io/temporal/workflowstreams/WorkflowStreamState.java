package io.temporal.workflowstreams;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.temporal.common.Experimental;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A serializable snapshot of stream state for continue-as-new. Thread a {@code WorkflowStreamState}
 * field through your workflow input and pass it to {@link
 * WorkflowStream#newInstance(WorkflowStreamState)}.
 *
 * <p>Field names are part of the cross-language wire protocol; this type must serialize to JSON
 * with exactly these names.
 */
@Experimental
public final class WorkflowStreamState {
  @JsonProperty("log")
  public List<WireItem> log = new ArrayList<>();

  @JsonProperty("base_offset")
  public long baseOffset;

  @JsonProperty("publisher_sequences")
  public Map<String, Long> publisherSequences = new HashMap<>();

  /** Unix seconds of the last batch accepted from each publisher. */
  @JsonProperty("publisher_last_seen")
  public Map<String, Double> publisherLastSeen = new HashMap<>();

  public WorkflowStreamState() {}
}
