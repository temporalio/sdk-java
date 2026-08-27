package io.temporal.internal.payload.storage;

import com.google.common.base.Throwables;
import com.google.protobuf.Message;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.sdk.v1.ExternalStorageReference;
import io.temporal.common.CancellationToken;
import io.temporal.internal.payload.visitor.MessageVisitor;
import io.temporal.internal.payload.visitor.PayloadVisitorOptions;
import io.temporal.internal.payload.visitor.PayloadVisitors;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;

/**
 * External storage offloads large payloads via {@link StorageDriver}s. It walks messages using
 * {@link PayloadVisitors} transforming payloads to and from {@link ExternalStorageReference} using
 * {@link ExternalStoragePayloadTransformer}. Use {@link ExternalStorage} via {@link#create} to
 * configure external storage.
 */
public final class ExternalStorageRunner {
  private final ExternalStoragePayloadTransformer payloadTransformer;
  private final int payloadVisitConcurrency;

  public static ExternalStorageRunner create(ExternalStorage options) {
    return new ExternalStorageRunner(
        ExternalStoragePayloadTransformer.fromOptions(options),
        options.getMaxConcurrentPayloadVisits());
  }

  ExternalStorageRunner(
      ExternalStoragePayloadTransformer payloadTransformer, int payloadVisitConcurrency) {
    this.payloadTransformer = payloadTransformer;
    this.payloadVisitConcurrency = payloadVisitConcurrency;
  }

  public void store(
      Message.Builder builder,
      @Nullable StorageDriverTargetInfo target,
      @Nullable MessageVisitor<StorageDriverTargetInfo> targetVisitor,
      CancellationToken<CancellationException> cancellationToken) {
    getOrThrowIfCancelled(
        PayloadVisitors.visit(builder, storeOptions(target, targetVisitor, cancellationToken)),
        cancellationToken);
  }

  public <T extends Message> T retrieve(
      T message, CancellationToken<CancellationException> cancellationToken) {
    return getOrThrowIfCancelled(retrieveAsync(message, cancellationToken), cancellationToken);
  }

  public <T extends Message> CompletableFuture<T> retrieveAsync(
      T message, CancellationToken<CancellationException> cancellationToken) {
    return PayloadVisitors.visit(message, retrieveOptions(cancellationToken));
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
    try {
      CompletableFuture.anyOf(future, cancellationToken.getCancellationFuture()).get();
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CancellationException("External storage operation interrupted");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      Throwables.throwIfUnchecked(cause);
      throw new CompletionException(cause);
    }
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
