package io.temporal.workflowstreams;

import io.temporal.common.Experimental;

/** Publishes to a single topic from workflow code. Obtained via {@link WorkflowStream#topic}. */
@Experimental
public final class WorkflowTopicHandle {
  private final String name;
  private final WorkflowStream stream;

  WorkflowTopicHandle(String name, WorkflowStream stream) {
    this.name = name;
    this.stream = stream;
  }

  /** Returns the topic name. */
  public String getName() {
    return name;
  }

  /**
   * Appends {@code value} to the stream on this topic. {@code value} is serialized by the stream's
   * payload converters (see {@link WorkflowStreamOptions.Builder#setPayloadConverters}), defaulting
   * to the standard set; a pre-built {@link io.temporal.api.common.v1.Payload} bypasses conversion.
   */
  public void publish(Object value) {
    stream.publishToTopic(name, value);
  }
}
