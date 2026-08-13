package io.temporal.workflowstreams;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.Experimental;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.ContinueAsNewOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflowstreams.internal.PayloadWire;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * The workflow-side stream object: an append-only, multi-topic log served to external publishers
 * (via signal), subscribers (via update), and offset queries (via query).
 *
 * <p>Construct it once with {@link #newInstance}, preferably in a {@link
 * io.temporal.workflow.WorkflowInit} constructor: the factory registers all three handlers on the
 * current workflow, and a {@code @WorkflowInit} constructor runs before any handler dispatch, so
 * polls arriving with the first workflow task are accepted. Constructing it at the start of the
 * workflow method also works — signals received earlier are buffered by the SDK — but polls and
 * offset queries are rejected until the stream exists.
 */
@Experimental
public final class WorkflowStream {

  /** A single decoded log entry held in workflow memory. */
  private static final class InternalEntry {
    final String topic;
    final Payload payload;

    InternalEntry(String topic, Payload payload) {
      this.topic = topic;
      this.payload = payload;
    }
  }

  private final DataConverter dataConverter;

  private final List<InternalEntry> log = new ArrayList<>();
  private long baseOffset;
  private final Map<String, Long> publisherSequences = new HashMap<>();
  private final Map<String, Double> publisherLastSeen = new HashMap<>();
  private boolean draining;

  private final Map<String, WorkflowTopicHandle> topicHandles = new HashMap<>();

  /** Constructs a stream with no prior state and default options. */
  public static WorkflowStream newInstance() {
    return newInstance(null, WorkflowStreamOptions.getDefaultInstance());
  }

  /**
   * Constructs a stream, restoring state carried across a continue-as-new boundary. {@code
   * priorState} may be null.
   */
  public static WorkflowStream newInstance(@Nullable WorkflowStreamState priorState) {
    return newInstance(priorState, WorkflowStreamOptions.getDefaultInstance());
  }

  /**
   * Constructs a {@code WorkflowStream} and registers its signal, update, and query handlers on the
   * current workflow. Pass {@code priorState} (which may be null) to restore state carried across a
   * continue-as-new boundary.
   */
  public static WorkflowStream newInstance(
      @Nullable WorkflowStreamState priorState, WorkflowStreamOptions options) {
    return new WorkflowStream(priorState, options);
  }

  private WorkflowStream(@Nullable WorkflowStreamState priorState, WorkflowStreamOptions options) {
    // A converter built only from PayloadConverters is codec-free, so workflow-published
    // items are never double-encoded against the worker's response codec.
    if (options.getPayloadConverters().length > 0) {
      this.dataConverter = new DefaultDataConverter(options.getPayloadConverters());
    } else {
      this.dataConverter = DefaultDataConverter.STANDARD_INSTANCE;
    }

    if (priorState != null) {
      baseOffset = priorState.baseOffset;
      if (priorState.log != null) {
        for (WireItem item : priorState.log) {
          log.add(new InternalEntry(item.topic, PayloadWire.decode(item.data)));
        }
      }
      if (priorState.publisherSequences != null) {
        publisherSequences.putAll(priorState.publisherSequences);
      }
      if (priorState.publisherLastSeen != null) {
        publisherLastSeen.putAll(priorState.publisherLastSeen);
      }
    }

    Workflow.registerListener(new HandlersImpl());
  }

  /**
   * Returns a handle for publishing to {@code name}. Repeated calls with the same name return the
   * same handle.
   */
  public WorkflowTopicHandle topic(String name) {
    return topicHandles.computeIfAbsent(name, n -> new WorkflowTopicHandle(n, this));
  }

  /** Unblocks all waiting poll handlers and rejects new polls. Used before continue-as-new. */
  public void detachPollers() {
    draining = true;
  }

  /**
   * Returns a serializable snapshot of stream state for continue-as-new. It drops per-publisher
   * sequence tracking for publishers that have not sent a batch within {@code publisherTtl}.
   */
  public WorkflowStreamState getState(Duration publisherTtl) {
    double now = Workflow.currentTimeMillis() / 1000.0;
    double ttlSeconds = publisherTtl.toMillis() / 1000.0;

    WorkflowStreamState state = new WorkflowStreamState();
    state.baseOffset = baseOffset;
    for (Map.Entry<String, Long> e : publisherSequences.entrySet()) {
      Double ts = publisherLastSeen.get(e.getKey());
      double lastSeen = ts == null ? 0 : ts;
      if (now - lastSeen < ttlSeconds) {
        state.publisherSequences.put(e.getKey(), e.getValue());
        state.publisherLastSeen.put(e.getKey(), lastSeen);
      }
    }

    for (InternalEntry entry : log) {
      // Per-item offset is re-derivable from baseOffset + index on reload.
      state.log.add(new WireItem(entry.topic, PayloadWire.encode(entry.payload), 0));
    }
    return state;
  }

  /**
   * Drains pollers, waits for in-flight handlers to finish, captures stream state, and
   * continues-as-new with the arguments built by {@code buildArgs}, so it can take a moment before
   * the current run ends. {@code buildArgs} receives the post-detach stream state and returns the
   * positional arguments for the new run; thread the {@link WorkflowStreamState} into your workflow
   * input so the stream survives the rollover.
   *
   * <p>State is captured with the default 15-minute publisher TTL. For a custom TTL, use the manual
   * recipe: {@link #detachPollers}, {@code Workflow.await(() ->
   * Workflow.isEveryHandlerFinished())}, {@link #getState}, then {@link Workflow#continueAsNew}.
   *
   * <p>This method never returns.
   */
  public void continueAsNew(Function<WorkflowStreamState, Object[]> buildArgs) {
    continueAsNew(null, buildArgs);
  }

  /** See {@link #continueAsNew(Function)}. */
  public void continueAsNew(
      @Nullable ContinueAsNewOptions options, Function<WorkflowStreamState, Object[]> buildArgs) {
    detachPollers();
    Workflow.await(() -> Workflow.isEveryHandlerFinished());
    WorkflowStreamState state = getState(WorkflowStreamConstants.DEFAULT_PUBLISHER_TTL);
    Workflow.continueAsNew(options, buildArgs.apply(state));
  }

  /**
   * Discards log entries before {@code upToOffset} and advances the base offset. After truncation,
   * polls requesting an offset before the new base receive a {@code TruncatedOffset} error.
   *
   * @throws io.temporal.failure.ApplicationFailure a non-retryable {@code TruncateOutOfRange}
   *     failure if {@code upToOffset} is past the end of the log
   */
  public void truncate(long upToOffset) {
    long logIndex = upToOffset - baseOffset;
    if (logIndex <= 0) {
      return;
    }
    if (logIndex > log.size()) {
      throw ApplicationFailure.newNonRetryableFailure(
          String.format(
              "cannot truncate to offset %d: only %d items exist",
              upToOffset, baseOffset + log.size()),
          WorkflowStreamConstants.ERROR_TYPE_TRUNCATE_OUT_OF_RANGE);
    }
    log.subList(0, (int) logIndex).clear();
    baseOffset = upToOffset;
  }

  void publishToTopic(String topic, Object value) {
    Payload payload;
    if (value instanceof Payload) {
      payload = (Payload) value;
    } else {
      payload =
          dataConverter
              .toPayload(value)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "workflowstreams: no payload converter accepted the published value"));
    }
    log.add(new InternalEntry(topic, payload));
  }

  private class HandlersImpl implements WorkflowStreamHandlers {
    @Override
    public void publish(PublishInput input) {
      if (input.publisherId != null && !input.publisherId.isEmpty()) {
        Long lastSeq = publisherSequences.get(input.publisherId);
        if (lastSeq != null && input.sequence <= lastSeq) {
          return; // duplicate — skip
        }
        publisherSequences.put(input.publisherId, input.sequence);
        publisherLastSeen.put(input.publisherId, Workflow.currentTimeMillis() / 1000.0);
      }
      if (input.items == null) {
        return;
      }
      for (PublishEntry entry : input.items) {
        Payload payload;
        try {
          payload = PayloadWire.decode(entry.data);
        } catch (RuntimeException e) {
          // A malformed entry would be a protocol violation; skip it rather than
          // corrupting the log.
          continue;
        }
        log.add(new InternalEntry(entry.topic, payload));
      }
    }

    @Override
    public void validatePoll(PollInput input) {
      if (draining) {
        throw ApplicationFailure.newNonRetryableFailure(
            "workflow is draining for continue-as-new",
            WorkflowStreamConstants.ERROR_TYPE_STREAM_DRAINING);
      }
    }

    @Override
    public PollResult poll(PollInput input) {
      // Wait until items at or after the requested offset are available, the requested
      // offset has been truncated away, or the stream is draining. baseOffset can advance
      // via truncate while we wait, so re-evaluate the requested position against the
      // current baseOffset on every check rather than capturing it once up front —
      // otherwise a truncation that passes the waiting offset leaves the condition
      // permanently unsatisfiable.
      boolean[] truncated = new boolean[1];
      Workflow.await(
          () -> {
            if (draining) {
              return true;
            }
            if (input.fromOffset != 0 && input.fromOffset < baseOffset) {
              // The subscriber's position was truncated, possibly while waiting.
              truncated[0] = true;
              return true;
            }
            // max clamps "from the beginning" to whatever is available.
            long logOffset = Math.max(input.fromOffset - baseOffset, 0);
            return log.size() > logOffset;
          });
      if (truncated[0]) {
        throw ApplicationFailure.newNonRetryableFailure(
            String.format(
                "requested offset %d has been truncated; current base offset is %d",
                input.fromOffset, baseOffset),
            WorkflowStreamConstants.ERROR_TYPE_TRUNCATED_OFFSET);
      }

      long logOffset = Math.max(input.fromOffset - baseOffset, 0);

      Set<String> topicSet =
          input.topics == null || input.topics.isEmpty() ? null : new HashSet<>(input.topics);

      List<WireItem> wireItems = new ArrayList<>();
      int size = 0;
      boolean moreReady = false;
      long nextOffset = baseOffset + log.size();

      for (long i = logOffset; i < log.size(); i++) {
        InternalEntry entry = log.get((int) i);
        if (topicSet != null && !topicSet.contains(entry.topic)) {
          continue;
        }
        long globalOffset = baseOffset + i;
        String encoded = PayloadWire.encode(entry.payload);
        int itemSize = PayloadWire.wireSize(encoded, entry.topic);
        if (size + itemSize > WorkflowStreamConstants.MAX_POLL_RESPONSE_BYTES
            && !wireItems.isEmpty()) {
          // Resume from this item on the next poll.
          nextOffset = globalOffset;
          moreReady = true;
          break;
        }
        size += itemSize;
        wireItems.add(new WireItem(entry.topic, encoded, globalOffset));
      }

      return new PollResult(wireItems, nextOffset, moreReady);
    }

    @Override
    public long offset() {
      return baseOffset + log.size();
    }
  }
}
