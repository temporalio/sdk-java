package io.temporal.workflowstreams;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.common.Experimental;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.workflowstreams.internal.StreamPublisher;
import java.util.HashMap;
import java.util.Map;

/**
 * Publishes to and subscribes from a workflow stream from external code (activities, starters,
 * other processes). The publish path is owned by an internal publisher that batches buffered items
 * and signals them to the workflow; the client itself holds the target workflow and the read
 * (subscribe/query) surface.
 *
 * <p>Close the client (e.g. via try-with-resources) to guarantee a final flush of buffered items.
 */
@Experimental
public final class WorkflowStreamClient implements AutoCloseable {
  private final WorkflowClient client;
  private final String workflowId;
  private final StreamPublisher publisher;

  private final Map<String, TopicHandle> topicHandles = new HashMap<>();

  /** Creates a client targeting {@code workflowId} through the given Temporal client. */
  public static WorkflowStreamClient newInstance(WorkflowClient client, String workflowId) {
    return newInstance(client, workflowId, WorkflowStreamClientOptions.getDefaultInstance());
  }

  /**
   * Creates a client targeting {@code workflowId} through the given Temporal client. The returned
   * client follows continue-as-new chains in {@link #subscribe}.
   */
  public static WorkflowStreamClient newInstance(
      WorkflowClient client, String workflowId, WorkflowStreamClientOptions options) {
    return new WorkflowStreamClient(client, workflowId, options);
  }

  /** See {@link #fromActivity(WorkflowStreamClientOptions)}. */
  public static WorkflowStreamClient fromActivity() {
    return fromActivity(WorkflowStreamClientOptions.getDefaultInstance());
  }

  /**
   * Creates a client targeting the current activity's parent workflow, using the activity's
   * Temporal client. Must be called from an activity thread.
   *
   * @throws IllegalStateException if not called from an activity, or if the activity has no parent
   *     workflow; in the latter case use {@link #newInstance} with an explicit workflow ID
   */
  public static WorkflowStreamClient fromActivity(WorkflowStreamClientOptions options) {
    ActivityExecutionContext context = Activity.getExecutionContext();
    String workflowId = context.getInfo().getWorkflowId();
    if (workflowId == null || workflowId.isEmpty()) {
      throw new IllegalStateException(
          "workflowstreams: fromActivity requires an activity scheduled by a workflow; otherwise"
              + " use newInstance with an explicit workflow ID");
    }
    return newInstance(context.getWorkflowClient(), workflowId, options);
  }

  private WorkflowStreamClient(
      WorkflowClient client, String workflowId, WorkflowStreamClientOptions options) {
    this.client = client;
    this.workflowId = workflowId;

    // A converter built only from PayloadConverters is codec-free, so items are never
    // double-encoded against the codec on the client's signal/update envelope.
    DataConverter dataConverter;
    if (options.getPayloadConverters().length > 0) {
      dataConverter = new DefaultDataConverter(options.getPayloadConverters());
    } else {
      dataConverter = DefaultDataConverter.STANDARD_INSTANCE;
    }

    WorkflowStub stub = client.newUntypedWorkflowStub(workflowId);
    this.publisher =
        new StreamPublisher(
            input -> stub.signal(WorkflowStreamConstants.PUBLISH_SIGNAL_NAME, input),
            dataConverter,
            options.getBatchInterval(),
            options.getMaxBatchSize(),
            options.getMaxRetryDuration());
  }

  /**
   * Returns a handle for publishing to and subscribing from {@code name}. Repeated calls with the
   * same name return the same handle.
   */
  public synchronized TopicHandle topic(String name) {
    return topicHandles.computeIfAbsent(name, n -> new TopicHandle(n, this));
  }

  /**
   * Sends buffered (and pending) items and waits for server confirmation. Returns once the items
   * buffered at call time have been signaled to the workflow and acknowledged.
   *
   * @throws FlushTimeoutException if a pending batch cannot be sent within the max retry duration
   */
  public void flush() {
    publisher.flush();
  }

  /** Queries the current global offset of the stream. */
  public long getOffset() {
    return client
        .newUntypedWorkflowStub(workflowId)
        .query(WorkflowStreamConstants.OFFSET_QUERY_NAME, Long.class);
  }

  /**
   * Returns a subscription that long-polls for new items. Iterate with:
   *
   * <pre>{@code
   * try (WorkflowStreamSubscription subscription = streamClient.subscribe(options)) {
   *   for (WorkflowStreamItem item : subscription) {
   *     // use item
   *   }
   * }
   * }</pre>
   *
   * <p>The iteration runs on the caller's thread. Each item carries the raw {@link
   * io.temporal.api.common.v1.Payload}; decode it with your data converter. The subscription ends
   * cleanly when the workflow reaches a terminal state, and automatically follows continue-as-new
   * chains.
   */
  public WorkflowStreamSubscription subscribe(SubscribeOptions options) {
    return new WorkflowStreamSubscription(client, workflowId, options);
  }

  /**
   * Stops the background publisher and drains any remaining items, guaranteeing a final flush. It
   * surfaces any deferred {@link FlushTimeoutException} from a prior background flush failure.
   */
  @Override
  public void close() {
    publisher.close();
  }

  void publishToTopic(String topic, Object value, boolean forceFlush) {
    publisher.publish(topic, value, forceFlush);
  }
}
