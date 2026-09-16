package io.temporal.workflowstreams;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.Experimental;
import java.lang.reflect.Type;
import javax.annotation.Nullable;

/**
 * Publishes to and subscribes from a single topic. Obtained via {@link WorkflowStreamClient#topic}.
 */
@Experimental
public final class TopicHandle<T> {
  private final String name;
  private final WorkflowStreamClient client;
  @Nullable private final Class<T> valueClass;
  @Nullable private final Type valueType;

  TopicHandle(String name, WorkflowStreamClient client) {
    this(name, client, null, null);
  }

  TopicHandle(
      String name,
      WorkflowStreamClient client,
      @Nullable Class<T> valueClass,
      @Nullable Type valueType) {
    this.name = name;
    this.client = client;
    this.valueClass = valueClass;
    this.valueType = valueType;
  }

  /** Returns the topic name. */
  public String getName() {
    return name;
  }

  /** Buffers {@code value} for publishing on this topic. See {@link #publish(Object, boolean)}. */
  public void publish(T value) {
    publish(value, false);
  }

  /**
   * Buffers {@code value} for publishing on this topic. {@code value} goes through the client's
   * payload converters immediately, so an unconvertible value fails this call rather than a later
   * background flush. Pass {@code forceFlush} to wake the publisher and send immediately.
   */
  public void publish(T value, boolean forceFlush) {
    client.publishToTopic(name, value, forceFlush);
  }

  /** Buffers a pre-built payload, bypassing item conversion. */
  public void publishPayload(Payload payload) {
    publishPayload(payload, false);
  }

  /** Buffers a pre-built payload, bypassing item conversion. */
  public void publishPayload(Payload payload, boolean forceFlush) {
    client.publishToTopic(name, payload, forceFlush);
  }

  /**
   * Returns a subscription over items on this topic, starting at {@code fromOffset}. See {@link
   * WorkflowStreamClient#subscribe(SubscribeOptions)}.
   */
  @SuppressWarnings("unchecked")
  public WorkflowStreamSubscription<T> subscribe(long fromOffset) {
    SubscribeOptions options =
        SubscribeOptions.newBuilder().setTopics(name).setFromOffset(fromOffset).build();
    if (valueClass == null) {
      return (WorkflowStreamSubscription<T>)
          (WorkflowStreamSubscription<?>) client.subscribe(options);
    }
    return client.subscribe(options, valueClass, valueType);
  }

  /**
   * Subscribes {@code listener} to items on this topic, starting at {@code fromOffset}, without
   * occupying a caller thread. See {@link WorkflowStreamClient#subscribe(SubscribeOptions,
   * WorkflowStreamListener)}.
   */
  public WorkflowStreamSubscriptionHandle subscribe(
      long fromOffset, WorkflowStreamListener<T> listener) {
    SubscribeOptions options =
        SubscribeOptions.newBuilder().setTopics(name).setFromOffset(fromOffset).build();
    if (valueClass == null) {
      @SuppressWarnings("unchecked")
      WorkflowStreamListener<Payload> rawListener =
          (WorkflowStreamListener<Payload>) (WorkflowStreamListener<?>) listener;
      return client.subscribe(options, rawListener);
    }
    return client.subscribe(options, valueClass, valueType, listener);
  }
}
