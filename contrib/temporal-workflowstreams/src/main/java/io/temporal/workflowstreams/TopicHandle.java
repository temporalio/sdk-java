package io.temporal.workflowstreams;

import io.temporal.common.Experimental;

/**
 * Publishes to and subscribes from a single topic. Obtained via {@link WorkflowStreamClient#topic}.
 */
@Experimental
public final class TopicHandle {
  private final String name;
  private final WorkflowStreamClient client;

  TopicHandle(String name, WorkflowStreamClient client) {
    this.name = name;
    this.client = client;
  }

  /** Returns the topic name. */
  public String getName() {
    return name;
  }

  /** Buffers {@code value} for publishing on this topic. See {@link #publish(Object, boolean)}. */
  public void publish(Object value) {
    publish(value, false);
  }

  /**
   * Buffers {@code value} for publishing on this topic. {@code value} goes through the client's
   * payload converters immediately, so an unconvertible value fails this call rather than a later
   * background flush; a pre-built {@link io.temporal.api.common.v1.Payload} bypasses conversion.
   * Pass {@code forceFlush} to wake the publisher and send immediately.
   */
  public void publish(Object value, boolean forceFlush) {
    client.publishToTopic(name, value, forceFlush);
  }

  /**
   * Returns a subscription over items on this topic, starting at {@code fromOffset}. See {@link
   * WorkflowStreamClient#subscribe(SubscribeOptions)}.
   */
  public WorkflowStreamSubscription subscribe(long fromOffset) {
    return client.subscribe(
        SubscribeOptions.newBuilder().setTopics(name).setFromOffset(fromOffset).build());
  }

  /**
   * Subscribes {@code listener} to items on this topic, starting at {@code fromOffset}, without
   * occupying a caller thread. See {@link WorkflowStreamClient#subscribe(SubscribeOptions,
   * WorkflowStreamListener)}.
   */
  public WorkflowStreamSubscriptionHandle subscribe(
      long fromOffset, WorkflowStreamListener listener) {
    return client.subscribe(
        SubscribeOptions.newBuilder().setTopics(name).setFromOffset(fromOffset).build(), listener);
  }
}
