package io.temporal.internal.payload.storage;

import com.google.common.base.Throwables;
import com.google.protobuf.Message;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.sdk.v1.ExternalStorageReference;
import io.temporal.common.CancellationToken;
import io.temporal.internal.common.AsyncSemaphore;
import io.temporal.internal.payload.visitor.MessageVisitor;
import io.temporal.internal.payload.visitor.PayloadVisitorOptions;
import io.temporal.internal.payload.visitor.PayloadVisitors;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;

/**
 * External storage offloads large payloads via {@link StorageDriver}s. It walks messages using
 * {@link PayloadVisitors} transforming payloads to and from {@link ExternalStorageReference} using
 * {@link ExternalStoragePayloadTransformer}. Use {@link ExternalStorage} via {@link #create} to
 * configure external storage.
 */
public final class ExternalStorageRunner {
  /** Visits every payload at once. ExternalStorageConcurrency does the limiting. */
  private static final int UNBOUNDED_PAYLOAD_VISITS = Integer.MAX_VALUE;

  /** Shared by every runner built from the same ExternalStorage. */
  private static final Map<ExternalStorage, AsyncSemaphore> PER_INSTANCE_SEMAPHORES =
      Collections.synchronizedMap(new WeakHashMap<>());

  private final ExternalStoragePayloadTransformer payloadTransformer;
  private final int maxOperationsPerMessage;

  /** Shared with every other runner built from the same ExternalStorage. */
  private final AsyncSemaphore perInstanceSemaphore;

  /** Returns a runner that shares its limits with others built from {@code options}. */
  public static ExternalStorageRunner create(ExternalStorage options) {
    return new ExternalStorageRunner(
        ExternalStoragePayloadTransformer.fromOptions(options),
        options.getConcurrency().getMaxOperationsPerMessage(),
        perInstanceSemaphoreFor(options));
  }

  /** Returns the limit shared by every runner built from {@code options}. */
  private static AsyncSemaphore perInstanceSemaphoreFor(ExternalStorage options) {
    return PER_INSTANCE_SEMAPHORES.computeIfAbsent(
        options, o -> new AsyncSemaphore(o.getConcurrency().getMaxDriverOperations()));
  }

  ExternalStorageRunner(
      ExternalStoragePayloadTransformer payloadTransformer,
      int maxOperationsPerMessage,
      AsyncSemaphore perInstanceSemaphore) {
    this.payloadTransformer = payloadTransformer;
    this.maxOperationsPerMessage = maxOperationsPerMessage;
    this.perInstanceSemaphore = perInstanceSemaphore;
  }

  /** Returns limits scoped to a single message. */
  private MessageStorageLimits newMessageLimits() {
    return new MessageStorageLimits(maxOperationsPerMessage, perInstanceSemaphore);
  }

  public void store(
      Message.Builder builder,
      @Nullable StorageDriverTargetInfo target,
      @Nullable MessageVisitor<StorageDriverTargetInfo> targetVisitor,
      CancellationToken<CancellationException> cancellationToken) {
    getOrThrowIfCancelled(
        storeAsync(builder, target, targetVisitor, cancellationToken), cancellationToken);
  }

  public CompletableFuture<Void> storeAsync(
      Message.Builder builder,
      @Nullable StorageDriverTargetInfo target,
      @Nullable MessageVisitor<StorageDriverTargetInfo> targetVisitor,
      CancellationToken<CancellationException> cancellationToken) {
    return PayloadVisitors.visit(
        builder, storeOptions(target, targetVisitor, cancellationToken, newMessageLimits()));
  }

  public <T extends Message> T retrieve(
      T message, CancellationToken<CancellationException> cancellationToken) {
    return getOrThrowIfCancelled(retrieveAsync(message, cancellationToken), cancellationToken);
  }

  public <T extends Message> CompletableFuture<T> retrieveAsync(
      T message, CancellationToken<CancellationException> cancellationToken) {
    return PayloadVisitors.visit(message, retrieveOptions(cancellationToken, newMessageLimits()));
  }

  /**
   * Throws {@link ExternalStorageNotConfiguredException} if {@code message} contains any reference
   * payload. Used at inbound task boundaries when external storage is not configured.
   */
  public static void throwIfContainsReference(Message message) {
    PayloadVisitorOptions<Void> options =
        PayloadVisitorOptions.<Void>newBuilder(
                (context, payloads) -> {
                  for (Payload payload : payloads) {
                    if (ExternalStorageReferences.isReference(payload)) {
                      throw new ExternalStorageNotConfiguredException();
                    }
                  }
                  return CompletableFuture.completedFuture(payloads);
                })
            .setSkipSearchAttributes(true)
            .build();
    try {
      PayloadVisitors.visit(message.toBuilder(), options).join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      Throwables.throwIfUnchecked(cause);
      throw e;
    }
  }

  private static <T> T getOrThrowIfCancelled(
      CompletableFuture<T> future, CancellationToken<CancellationException> cancellationToken) {
    CompletableFuture<Void> cancellation = cancellationToken.getCancellationFuture();
    try {
      CompletableFuture.anyOf(future, cancellation).get();
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      CancellationException cancelled =
          new CancellationException("External storage operation interrupted");
      cancelled.initCause(e);
      throw cancelled;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      Throwables.throwIfUnchecked(cause);
      throw new CompletionException(cause);
    } finally {
      cancellation.complete(null);
    }
  }

  private PayloadVisitorOptions<StorageDriverTargetInfo> storeOptions(
      @Nullable StorageDriverTargetInfo target,
      @Nullable MessageVisitor<StorageDriverTargetInfo> targetVisitor,
      CancellationToken<CancellationException> cancellationToken,
      MessageStorageLimits limits) {
    return PayloadVisitorOptions.<StorageDriverTargetInfo>newBuilder(
            (visitedTarget, payloads) ->
                payloadTransformer.store(payloads, visitedTarget, cancellationToken, limits))
        .setInitialContext(target)
        .setMessageVisitor(targetVisitor)
        .setConcurrency(UNBOUNDED_PAYLOAD_VISITS)
        .setSkipSearchAttributes(true)
        .build();
  }

  private PayloadVisitorOptions<Void> retrieveOptions(
      CancellationToken<CancellationException> cancellationToken, MessageStorageLimits limits) {
    return PayloadVisitorOptions.<Void>newBuilder(
            (context, payloads) -> payloadTransformer.retrieve(payloads, cancellationToken, limits))
        .setConcurrency(UNBOUNDED_PAYLOAD_VISITS)
        .setSkipSearchAttributes(true)
        .build();
  }
}
