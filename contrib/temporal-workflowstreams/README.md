# Workflow Streams

A durable publish/subscribe log hosted inside a Temporal Workflow.

External code (activities, starters, other processes) publishes messages to
named topics via **signals**; subscribers long-poll for new items via
**updates**; a **query** exposes the current offset. The stream is backed by
Temporal's durable execution, giving ordered, durable, exactly-once delivery
with client-side batching, publisher dedup, continue-as-new survival,
truncation, and ~1 MB response paging.

It is well suited to durable event streams whose cost scales with durable
batches rather than message count. Each poll round-trip costs ~100 ms of
latency, so it is not intended for ultra-low-latency streaming.

All APIs in this module are experimental and may change.

## Workflow side

Construct a `WorkflowStream` once in a `@WorkflowInit` constructor. The factory
registers the publish signal, poll update, and offset query handlers, and a
`@WorkflowInit` constructor runs before any handler dispatch, so polls and
offset queries arriving with the first workflow task (e.g. from
update-with-start) are accepted rather than rejected.

```java
public class MyInput {
  public int itemsProcessed; // your own workflow state
  public WorkflowStreamState streamState;
}

public class MyWorkflowImpl implements MyWorkflow {
  private final WorkflowStream stream;

  @WorkflowInit
  public MyWorkflowImpl(MyInput input) {
    stream = WorkflowStream.newInstance(input.streamState);
  }

  @Override
  public void execute(MyInput input) {
    // Optionally publish from workflow code:
    stream.topic("events").publish("hello from the workflow");

    // Run your workflow; the stream serves external publishers and subscribers
    // for as long as the workflow is running. Block until your workflow's exit
    // condition is met (here, a `done` flag set elsewhere, e.g. by a signal).
    Workflow.await(() -> done);
  }
}
```

Constructing the stream at the top of the workflow method also works — signals
received earlier are buffered by the SDK — but polls and offset queries are
rejected until the stream exists, so prefer `@WorkflowInit`.

For workflows that use continue-as-new, the stream's log and offsets must be
carried across each boundary, since continue-as-new starts a fresh run with an
empty history. This is a round-trip with two halves:

- **Capture** the state when rolling over. Instead of calling
  `Workflow.continueAsNew` directly, call `stream.continueAsNew`. It drains
  pollers, waits for in-flight handlers, snapshots the current stream state, and
  hands it to your callback, which builds the argument list for the next run.
  The callback is where you assemble the full input — carry forward your own
  workflow state alongside the captured `state`:

  ```java
  stream.continueAsNew(state -> {
    MyInput next = new MyInput();
    next.itemsProcessed = itemsProcessed; // your own state, carried across the boundary
    next.streamState = state;             // the captured stream state
    return new Object[] {next};
  });
  ```

- **Restore** it on the next run. That `MyInput` arrives as the next run's
  input, and its `streamState` field is the value already passed to
  `WorkflowStream.newInstance` in the example above. It is `null` on a fresh
  start and non-null after a roll-over, so the stream rehydrates the log
  automatically.

The `WorkflowStreamState` field is what gives the captured stream state
somewhere to live between runs; the other fields on `MyInput` are your own and
are threaded through the same way.

## Publishing (client side)

From an activity, use `fromActivity` to target the parent workflow:

```java
public void publishActivity() {
  try (WorkflowStreamClient client = WorkflowStreamClient.fromActivity()) {
    TopicHandle topic = client.topic("events");
    for (int i = 0; i < 100; i++) {
      topic.publish("item " + i);
    }
  } // client.close() is called on completion, which flushes the remaining buffer
}
```

From a starter or any code with a `WorkflowClient`, use `newInstance` with an
explicit workflow ID:

```java
try (WorkflowStreamClient client = WorkflowStreamClient.newInstance(workflowClient, workflowId)) {
  client.topic("events").publish("from outside", /* forceFlush */ true);
}
```

Items are buffered and flushed automatically every batch interval (default 2s),
when the buffer reaches the max batch size, on `forceFlush`, on an explicit
`flush()`, or on `close()`.

## Subscribing

There are two subscriber APIs over one shared poll engine: a non-blocking
listener and a blocking iterator. Neither occupies a thread while a poll is
blocked on the server — polling runs on a small executor shared by all of a
client's subscriptions (2 daemon threads by default; see `pollExecutor`) — so
many concurrent subscriptions do not mean many threads. Either way, the
subscription ends cleanly when the workflow reaches a terminal state,
automatically follows continue-as-new chains, recovers from truncation by
restarting from the current base offset, and also ends when the owning
`WorkflowStreamClient` is closed.

Items carry the raw `io.temporal.api.common.v1.Payload`; decode at the call
site with your data converter. Offsets are **global** (across all topics), not
per-topic.

### Listener (non-blocking)

Pass a `WorkflowStreamListener` to `subscribe` to have items delivered on the
poll executor. Callbacks are serialized (never invoked concurrently) and must
not block; the `CompletionStage` returned by `onNext` is the backpressure
boundary — return `null` (or a completed stage) to receive the next item
immediately, or a pending stage to defer further delivery and polling until it
completes:

```java
SubscribeOptions options = SubscribeOptions.newBuilder()
    .setTopics("events") // unset = all topics
    .build();
WorkflowStreamSubscriptionHandle handle =
    client.subscribe(
        options,
        new WorkflowStreamListener() {
          @Override
          public CompletionStage<Void> onNext(WorkflowStreamItem item) {
            String value =
                DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
                    item.getPayload(), String.class, String.class);
            System.out.printf(
                "offset=%d topic=%s value=%s%n", item.getOffset(), item.getTopic(), value);
            return null; // or a pending stage to apply backpressure
          }

          @Override
          public void onCompleted() {
            System.out.println("stream ended");
          }
        });
```

`handle.close()` stops the subscription before the next poll (without calling
`onCompleted`); `handle.getDoneFuture()` completes when the subscription ends —
normally on a clean end or close, exceptionally with the failure passed to
`onError`.

### Iterator (blocking)

For synchronous consumers, `subscribe` without a listener returns a blocking,
single-use subscription; the consuming thread blocks waiting for items while
polling still runs on the shared executor:

```java
try (WorkflowStreamSubscription subscription = client.subscribe(options)) {
  for (WorkflowStreamItem item : subscription) {
    String value =
        DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
            item.getPayload(), String.class, String.class);
    System.out.printf("offset=%d topic=%s value=%s%n", item.getOffset(), item.getTopic(), value);
  }
}
```

`close()` stops it before the next poll; items already fetched still drain. An
unrecoverable poll failure is rethrown from `hasNext()`.

## Options

| Option | Default | Meaning |
| --- | --- | --- |
| `batchInterval` | 2s | Automatic flush interval |
| `maxBatchSize` | unset | Flush once the buffer reaches this size |
| `maxRetryDuration` | 10m | Max time to retry a failed flush before `FlushTimeoutException`. Must be < the workflow's publisher TTL (15m) to preserve exactly-once delivery |
| `payloadConverters` | standard set | Per-item serialization. Payload conversion only — the client's codec chain runs once on the envelope, never per item |
| `pollExecutor` | 2 daemon threads, client-owned | Scheduler shared by the client's subscriptions. It runs the short update-admission and delivery steps and poll cooldowns — never held during the long poll itself. A user-supplied executor is never shut down by the client; supply a bigger pool for many subscriptions against slow workflows |
| `SubscribeOptions.pollCooldown` | 100ms | Min interval between polls |

## Cross-language protocol

The handler names (`WorkflowStreamConstants.PUBLISH_SIGNAL_NAME`,
`POLL_UPDATE_NAME`, `OFFSET_QUERY_NAME`), the JSON envelope field names, and
the per-item payload encoding (base64 of the serialized
`temporal.api.common.v1.Payload`) match other languages' packages
exactly, so a Java publisher or subscriber interoperates with a workflow
written in any of them and vice versa. The data converter codec chain
(encryption, compression) runs once on the signal/update envelope — never per
item — so payloads are not double-encoded.

One Java-specific caveat: the protocol envelope types are serialized by the
workflow's and client's *configured* data converter. The default Jackson JSON
converter produces the wire-compatible snake_case field names (the types are
annotated with `@JsonProperty`); if you configure a non-Jackson JSON converter,
it must produce the same field names for cross-language interop.
