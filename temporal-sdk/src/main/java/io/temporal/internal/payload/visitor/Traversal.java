package io.temporal.internal.payload.visitor;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import io.temporal.api.common.v1.Payload;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Mutable state for one traversal, called into by the generated per-message visitors.
 *
 * <p>A single-threaded walk records a visit job and a write-back for each payload sequence it
 * finds; {@link #execute()} then runs the visitor calls (optionally with bounded concurrency) and
 * finally applies the write-backs in walk order, so the non-thread-safe builders are never mutated
 * concurrently.
 */
final class Traversal {
  // The payload visitor is null for a message-only traversal (see MessageVisitors); in that case
  // the payload seams are skipped and only the per-message MessageVisitor fires.
  private final PayloadVisitor<Object> payloadVisitor;
  private final MessageVisitor<Object> messageVisitor;
  private final Map<String, MessageRegistryEntry> registry;
  final boolean skipSearchAttributes;
  final boolean skipHeaders;
  private final int concurrency;
  private final Executor executor;

  private final List<LeafJob> jobs = new ArrayList<>();
  private final List<Runnable> writeBacks = new ArrayList<>();
  private Object currentContext;

  @SuppressWarnings("unchecked")
  Traversal(
      PayloadVisitor<?> payloadVisitor,
      MessageVisitor<?> messageVisitor,
      Object initialContext,
      boolean skipSearchAttributes,
      boolean skipHeaders,
      int concurrency,
      Executor executor,
      Map<String, MessageRegistryEntry> registry) {
    if (concurrency < 1) {
      throw new IllegalArgumentException("concurrency must be at least 1, got " + concurrency);
    }
    if (concurrency > 1 && executor == null) {
      throw new IllegalArgumentException("executor is required when concurrency is greater than 1");
    }
    this.payloadVisitor = (PayloadVisitor<Object>) payloadVisitor;
    this.messageVisitor = (MessageVisitor<Object>) messageVisitor;
    this.currentContext = initialContext;
    this.skipSearchAttributes = skipSearchAttributes;
    this.skipHeaders = skipHeaders;
    this.concurrency = concurrency;
    this.executor = executor;
    this.registry = registry;
  }

  // --- Structural walk: called by generated code ---

  /** Dispatch to the generated visitor for {@code builder}'s type; no-op if it has no payloads. */
  void dispatch(Message.Builder builder) {
    MessageRegistryEntry entry = registry.get(builder.getDescriptorForType().getFullName());
    if (entry != null) {
      entry.visitor.visit(this, builder);
    }
  }

  /**
   * Run the message visitor for {@code message}, narrowing the scoped context; returns the value to
   * restore.
   */
  Object enter(MessageOrBuilder message) {
    Object previous = currentContext;
    if (messageVisitor != null) {
      currentContext = messageVisitor.onEnter(previous, message);
    }
    return previous;
  }

  /** Restore the scoped context to {@code previous} when leaving a message's subtree. */
  void exit(Object previous) {
    currentContext = previous;
  }

  /** Record a visit of a payload sequence ({@code Payloads} or {@code repeated Payload}). */
  void payloads(List<Payload> batch, Consumer<List<Payload>> writeBack) {
    if (payloadVisitor == null) {
      return; // message-only traversal: payload seams are inert
    }
    LeafJob job = new LeafJob(batch, currentContext, false);
    jobs.add(job);
    writeBacks.add(() -> writeBack.accept(job.result));
  }

  /**
   * Record a visit of a singular payload field. The visitor must return exactly one payload for
   * such a field (enforced in {@link #runJob}), which the consumer writes back.
   */
  void singlePayload(Payload value, Consumer<Payload> writeBack) {
    if (payloadVisitor == null) {
      return; // message-only traversal: payload seams are inert
    }
    LeafJob job = new LeafJob(Collections.singletonList(value), currentContext, true);
    jobs.add(job);
    writeBacks.add(() -> writeBack.accept(job.result.get(0)));
  }

  /** Append a deferred write-back, applied (single-threaded) after all visits and in walk order. */
  void deferWriteBack(Runnable writeBack) {
    writeBacks.add(writeBack);
  }

  /** Unpack a {@code google.protobuf.Any}, traverse its contents, and re-pack it after visits. */
  void any(Any.Builder anyBuilder) {
    String typeUrl = anyBuilder.getTypeUrl();
    int slash = typeUrl.lastIndexOf('/');
    String fullName = slash >= 0 ? typeUrl.substring(slash + 1) : typeUrl;
    MessageRegistryEntry entry = registry.get(fullName);
    if (entry == null) {
      // Unknown type, or a type with no payloads; leave the Any untouched.
      return;
    }
    Message.Builder inner = entry.newBuilder.get();
    try {
      inner.mergeFrom(anyBuilder.getValue());
    } catch (InvalidProtocolBufferException e) {
      throw new VisitorException("failed to unpack Any of type " + fullName, e);
    }
    entry.visitor.visit(this, inner);
    deferWriteBack(() -> anyBuilder.setValue(inner.build().toByteString()));
  }

  // --- Execution: visitor calls (phase 2) then write-backs (phase 3) ---

  void execute() {
    if (jobs.isEmpty()) {
      return;
    }
    if (concurrency <= 1 || jobs.size() == 1) {
      for (LeafJob job : jobs) {
        runJob(job);
      }
    } else {
      executeConcurrently();
    }
    for (Runnable writeBack : writeBacks) {
      writeBack.run();
    }
  }

  private void executeConcurrently() {
    // concurrency > 1 and a non-null executor are guaranteed by the constructor's validation.
    Executor pool = executor;
    Semaphore semaphore = new Semaphore(concurrency);
    AtomicReference<Throwable> firstError = new AtomicReference<>();
    List<CompletableFuture<Void>> futures = new ArrayList<>(jobs.size());
    for (LeafJob job : jobs) {
      if (firstError.get() != null) {
        break;
      }
      try {
        semaphore.acquire();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        firstError.compareAndSet(null, e);
        break;
      }
      if (firstError.get() != null) {
        semaphore.release();
        break;
      }
      futures.add(
          CompletableFuture.runAsync(
              () -> {
                try {
                  runJob(job);
                } catch (Throwable t) {
                  firstError.compareAndSet(null, t);
                } finally {
                  semaphore.release();
                }
              },
              pool));
    }
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    Throwable error = firstError.get();
    if (error instanceof RuntimeException) {
      throw (RuntimeException) error;
    }
    if (error instanceof Error) {
      throw (Error) error;
    }
    if (error != null) {
      // The only checked exception that can reach here is an InterruptedException from acquiring
      // the semaphore.
      throw new VisitorException("payload visit interrupted", error);
    }
  }

  private void runJob(LeafJob job) {
    List<Payload> result = payloadVisitor.visit(job.context, job.input);
    if (result == null) {
      throw new IllegalStateException("payload visitor returned null");
    }
    if (job.single && result.size() != 1) {
      throw new IllegalStateException(
          "single-payload field requires exactly 1 returned payload, got " + result.size());
    }
    job.result = result;
  }

  /** A single recorded visitor call and the slot its result is written into. */
  private static final class LeafJob {
    final List<Payload> input;
    final Object context;
    final boolean single;
    volatile List<Payload> result;

    LeafJob(List<Payload> input, Object context, boolean single) {
      this.input = input;
      this.context = context;
      this.single = single;
    }
  }
}
