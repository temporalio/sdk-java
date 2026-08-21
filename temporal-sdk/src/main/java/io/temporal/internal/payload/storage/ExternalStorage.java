package io.temporal.internal.payload.storage;

import com.google.common.base.Throwables;
import com.google.protobuf.Message;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.sdk.v1.ExternalStorageReference;
import io.temporal.common.CancellationToken;
import io.temporal.internal.concurrent.structured.CancelSource;
import io.temporal.internal.payload.visitor.MessageVisitor;
import io.temporal.internal.payload.visitor.PayloadVisitorOptions;
import io.temporal.internal.payload.visitor.PayloadVisitors;
import io.temporal.payload.storage.ExternalStorageOptions;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

/**
 * External storage offloads large payloads via {@link StorageDriver}s. It walks messages using
 * {@link PayloadVisitors} transforming payloads to and from {@link ExternalStorageReference} using
 * {@link ExternalStoragePayloadTransformer}. Use {@link ExternalStorageOptions} via {@link#create}
 * to configure external storage.
 */
public final class ExternalStorage {
  private final ExternalStoragePayloadTransformer payloadTransformer;
  private final int payloadVisitConcurrency;

  public static ExternalStorage create(ExternalStorageOptions options) {
    return new ExternalStorage(
        ExternalStoragePayloadTransformer.fromOptions(options),
        options.getMaxConcurrentPayloadVisits());
  }

  ExternalStorage(
      ExternalStoragePayloadTransformer payloadTransformer, int payloadVisitConcurrency) {
    this.payloadTransformer = payloadTransformer;
    this.payloadVisitConcurrency = payloadVisitConcurrency;
  }

  public <T extends Message> T storeBlocking(T message, @Nullable StorageDriverTargetInfo target) {
    return storeBlocking(message, target, (Duration) null);
  }

  public <T extends Message> T storeBlocking(
      T message, @Nullable StorageDriverTargetInfo target, @Nullable Duration timeout) {
    CancelSource<CancellationException> cancel = new CancelSource<>(CancellationException::new);
    return getOrCancel(store(message, target, cancel.token()), cancel, timeout);
  }

  public <T extends Message> T storeBlocking(
      T message,
      @Nullable StorageDriverTargetInfo target,
      @Nullable MessageVisitor<StorageDriverTargetInfo> targetVisitor) {
    CancelSource<CancellationException> cancel = new CancelSource<>(CancellationException::new);
    return getOrCancel(
        PayloadVisitors.visit(message, storeOptions(target, targetVisitor, cancel.token())),
        cancel,
        null);
  }

  public <T extends Message> T retrieveBlocking(T message) {
    CancelSource<CancellationException> cancel = new CancelSource<>(CancellationException::new);
    return getOrCancel(retrieve(message, cancel.token()), cancel, null);
  }

  public <T extends Message> CompletableFuture<T> retrieveAsync(T message) {
    return retrieve(message, CancellationToken.none());
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
                      CompletableFuture<List<Payload>> found = new CompletableFuture<>();
                      found.completeExceptionally(new ExternalStorageNotConfiguredException());
                      return found;
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

  private static <T> T getOrCancel(
      CompletableFuture<T> future,
      CancelSource<CancellationException> cancel,
      @Nullable Duration timeout) {
    try {
      return timeout == null ? future.get() : future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      cancel.cancel();
      Thread.currentThread().interrupt();
      throw new CancellationException("External storage operation interrupted");
    } catch (TimeoutException e) {
      cancel.cancel();
      throw new CancellationException("External storage operation timed out after " + timeout);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      Throwables.throwIfUnchecked(cause);
      throw new CompletionException(cause);
    }
  }

  <T extends Message> CompletableFuture<T> store(
      T message,
      @Nullable StorageDriverTargetInfo target,
      CancellationToken<CancellationException> cancellationToken) {
    return PayloadVisitors.visit(message, storeOptions(target, null, cancellationToken));
  }

  CompletableFuture<Void> store(
      Message.Builder builder,
      @Nullable StorageDriverTargetInfo target,
      CancellationToken<CancellationException> cancellationToken) {
    return PayloadVisitors.visit(builder, storeOptions(target, null, cancellationToken));
  }

  <T extends Message> CompletableFuture<T> retrieve(
      T message, CancellationToken<CancellationException> cancellationToken) {
    return PayloadVisitors.visit(message, retrieveOptions(cancellationToken));
  }

  CompletableFuture<Void> retrieve(
      Message.Builder builder, CancellationToken<CancellationException> cancellationToken) {
    return PayloadVisitors.visit(builder, retrieveOptions(cancellationToken));
  }

  private PayloadVisitorOptions<StorageDriverTargetInfo> storeOptions(
      @Nullable StorageDriverTargetInfo target,
      @Nullable MessageVisitor<StorageDriverTargetInfo> targetVisitor,
      CancellationToken<CancellationException> cancellationToken) {
    return PayloadVisitorOptions.<StorageDriverTargetInfo>newBuilder(
            (visitedTarget, payloads) ->
                payloadTransformer.store(payloads, visitedTarget, cancellationToken))
        .setInitialContext(target)
        .setMessageVisitor(targetVisitor)
        .setConcurrency(payloadVisitConcurrency)
        .setSkipSearchAttributes(true)
        .build();
  }

  private PayloadVisitorOptions<Void> retrieveOptions(
      CancellationToken<CancellationException> cancellationToken) {
    return PayloadVisitorOptions.<Void>newBuilder(
            (context, payloads) -> payloadTransformer.retrieve(payloads, cancellationToken))
        .setConcurrency(payloadVisitConcurrency)
        .setSkipSearchAttributes(true)
        .build();
  }
}
