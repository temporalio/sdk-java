package io.temporal.internal.payload.visitor;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import io.temporal.api.common.v1.Payload;
import io.temporal.internal.common.AsyncSemaphore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Mutable state for one traversal, called into by the generated per-message visitors.
 *
 * <p>The single-threaded walk only records a job and a write-back per payload sequence; {@link
 * #execute()} runs the visits and applies the write-backs afterward in walk order, so the
 * non-thread-safe builders are never mutated concurrently. Visits are asynchronous, so the engine
 * needs no executor — it only bounds how many of their futures are outstanding.
 */
final class Traversal {
  // Null for a message-only traversal: payload seams are skipped, only the MessageVisitor fires.
  private final PayloadVisitor<Object> payloadVisitor;
  private final MessageVisitor<Object> messageVisitor;
  private final Map<String, MessageRegistryEntry> registry;
  final boolean skipSearchAttributes;
  final boolean skipHeaders;
  private final int concurrency;

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
      Map<String, MessageRegistryEntry> registry) {
    if (concurrency < 1) {
      throw new IllegalArgumentException("concurrency must be at least 1, got " + concurrency);
    }
    this.payloadVisitor = (PayloadVisitor<Object>) payloadVisitor;
    this.messageVisitor = (MessageVisitor<Object>) messageVisitor;
    this.currentContext = initialContext;
    this.skipSearchAttributes = skipSearchAttributes;
    this.skipHeaders = skipHeaders;
    this.concurrency = concurrency;
    this.registry = registry;
  }

  // --- Structural walk: called by generated code ---

  /** No-op for a type with no payloads. */
  void dispatch(Message.Builder builder) {
    MessageRegistryEntry entry = registry.get(builder.getDescriptorForType().getFullName());
    if (entry != null) {
      entry.visitor.visit(this, builder);
    }
  }

  /** Narrows the scoped context; returns the value {@link #exit} restores. */
  Object enter(MessageOrBuilder message) {
    Object previous = currentContext;
    if (messageVisitor != null) {
      currentContext = messageVisitor.onEnter(previous, message);
    }
    return previous;
  }

  void exit(Object previous) {
    currentContext = previous;
  }

  /** Record a visit of a payload sequence (a {@code Payloads} or {@code repeated Payload}). */
  void payloads(List<Payload> batch, Consumer<List<Payload>> writeBack) {
    if (payloadVisitor == null) {
      return;
    }
    LeafJob job = new LeafJob(batch, currentContext, false);
    jobs.add(job);
    writeBacks.add(() -> writeBack.accept(job.result));
  }

  /** The visitor must return exactly one payload (checked in {@link #record}). */
  void singlePayload(Payload value, Consumer<Payload> writeBack) {
    if (payloadVisitor == null) {
      return;
    }
    LeafJob job = new LeafJob(Collections.singletonList(value), currentContext, true);
    jobs.add(job);
    writeBacks.add(() -> writeBack.accept(job.result.get(0)));
  }

  /** Applied after all visits, single-threaded, in walk order. */
  void deferWriteBack(Runnable writeBack) {
    writeBacks.add(writeBack);
  }

  /** Unpack a {@code google.protobuf.Any}, traverse its contents, and re-pack after visits. */
  void any(Any.Builder anyBuilder) {
    String typeUrl = anyBuilder.getTypeUrl();
    int slash = typeUrl.lastIndexOf('/');
    String fullName = slash >= 0 ? typeUrl.substring(slash + 1) : typeUrl;
    MessageRegistryEntry entry = registry.get(fullName);
    if (entry == null) {
      // Unknown or payload-free type: leave the Any untouched.
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

  // --- Execution: visits, then write-backs ---

  /**
   * Completes the returned future once the visits and write-backs are done. Blocks no thread of its
   * own: the caller decides how to wait and which executor to chain on. Write-backs run on whatever
   * thread completes the last visit (inline if the visits are synchronous). A visit failure aborts
   * the traversal — remaining visits unstarted, write-backs skipped — and completes the future
   * exceptionally with the original throwable.
   */
  CompletableFuture<Void> execute() {
    CompletableFuture<Void> visitsDone =
        jobs.isEmpty() ? CompletableFuture.completedFuture(null) : runVisits();
    CompletableFuture<Void> result = new CompletableFuture<>();
    visitsDone.whenComplete(
        (v, err) -> {
          if (err != null) {
            result.completeExceptionally(unwrap(err));
            return;
          }
          try {
            for (Runnable writeBack : writeBacks) {
              writeBack.run();
            }
            result.complete(null);
          } catch (Throwable t) {
            result.completeExceptionally(t);
          }
        });
    return result;
  }

  /** Run the visits with at most {@code concurrency} outstanding; fails with the first error. */
  private CompletableFuture<Void> runVisits() {
    AsyncSemaphore permits = new AsyncSemaphore(concurrency);
    AtomicReference<Throwable> firstError = new AtomicReference<>();
    List<CompletableFuture<Void>> all = new ArrayList<>(jobs.size());
    for (LeafJob job : jobs) {
      all.add(permits.acquire().thenCompose(p -> runJob(job, permits, firstError)));
    }
    return CompletableFuture.allOf(all.toArray(new CompletableFuture[0]))
        .thenCompose(
            v -> {
              Throwable error = firstError.get();
              if (error == null) {
                return CompletableFuture.completedFuture(null);
              }
              CompletableFuture<Void> failed = new CompletableFuture<>();
              failed.completeExceptionally(error);
              return failed;
            });
  }

  /** Runs one job under a held permit, releasing it exactly once when the visit settles. */
  private CompletableFuture<Void> runJob(
      LeafJob job, AsyncSemaphore permits, AtomicReference<Throwable> firstError) {
    if (firstError.get() != null) {
      permits.release();
      return CompletableFuture.completedFuture(null);
    }
    CompletableFuture<List<Payload>> visit;
    try {
      visit = payloadVisitor.visit(job.context, job.input);
    } catch (RuntimeException | Error t) {
      firstError.compareAndSet(null, t);
      permits.release();
      return CompletableFuture.completedFuture(null);
    }
    if (visit == null) {
      firstError.compareAndSet(null, new IllegalStateException("payload visitor returned null"));
      permits.release();
      return CompletableFuture.completedFuture(null);
    }
    return visit
        .handle(
            (result, err) -> {
              record(job, result, err, firstError);
              return (Void) null;
            })
        .whenComplete((v, e) -> permits.release());
  }

  private void record(
      LeafJob job, List<Payload> result, Throwable err, AtomicReference<Throwable> firstError) {
    if (err != null) {
      firstError.compareAndSet(null, unwrap(err));
    } else if (result == null) {
      firstError.compareAndSet(null, new IllegalStateException("payload visitor returned null"));
    } else if (job.single && result.size() != 1) {
      firstError.compareAndSet(
          null,
          new IllegalStateException(
              "single-payload field requires exactly 1 returned payload, got " + result.size()));
    } else {
      job.result = result;
    }
  }

  /** Strip the {@link CompletionException} a dependent stage wraps around its cause. */
  private static Throwable unwrap(Throwable t) {
    return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
  }

  /** A recorded visit and the slot its result lands in. */
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
