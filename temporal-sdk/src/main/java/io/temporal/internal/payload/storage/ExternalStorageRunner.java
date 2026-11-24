package io.temporal.internal.payload.storage;

import com.google.common.base.Throwables;
import com.google.protobuf.Message;
import io.temporal.api.common.v1.Header;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.sdk.v1.ExternalStorageReference;
import io.temporal.common.CancellationToken;
import io.temporal.internal.payload.visitor.MessageVisitor;
import io.temporal.internal.payload.visitor.PayloadVisitorOptions;
import io.temporal.internal.payload.visitor.PayloadVisitors;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import java.util.Set;
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
  private final ExternalStoragePayloadTransformer payloadTransformer;
  private final int payloadVisitConcurrency;
  private final boolean encodeReferences;

  /**
   * @param namespace namespace of the client or worker that will use this runner, exposed to
   *     drivers and selectors on store.
   */
  public static ExternalStorageRunner create(ExternalStorage options, String namespace) {
    return new ExternalStorageRunner(
        ExternalStoragePayloadTransformer.fromOptions(options, namespace),
        options.getMaxConcurrentPayloadVisits(),
        options.isEncodeReferences());
  }

  ExternalStorageRunner(
      ExternalStoragePayloadTransformer payloadTransformer,
      int payloadVisitConcurrency,
      boolean encodeReferences) {
    this.payloadTransformer = payloadTransformer;
    this.payloadVisitConcurrency = payloadVisitConcurrency;
    this.encodeReferences = encodeReferences;
  }

  /** Stores the payloads of {@code builder}, leaving the references that replace them plain. */
  public void store(
      Message.Builder builder,
      @Nullable StorageDriverTargetInfo target,
      @Nullable MessageVisitor<StorageDriverTargetInfo> targetVisitor,
      CancellationToken<CancellationException> cancellationToken) {
    store(builder, target, targetVisitor, cancellationToken, null);
  }

  /**
   * Stores the payloads of {@code builder}, then encodes the references that replace them with
   * {@code codec}.
   *
   * @param codec the codec that encoded the payloads of {@code builder}, or null if there is none
   */
  public void store(
      Message.Builder builder,
      @Nullable StorageDriverTargetInfo target,
      @Nullable MessageVisitor<StorageDriverTargetInfo> targetVisitor,
      @Nullable PayloadCodec codec,
      CancellationToken<CancellationException> cancellationToken) {
    getOrThrowIfCancelled(
        storeAsync(builder, target, targetVisitor, codec, cancellationToken), cancellationToken);
  }

  /**
   * Same as {@link #store(Message.Builder, StorageDriverTargetInfo, MessageVisitor, PayloadCodec,
   * CancellationToken)}, without waiting.
   */
  public CompletableFuture<Void> storeAsync(
      Message.Builder builder,
      @Nullable StorageDriverTargetInfo target,
      @Nullable MessageVisitor<StorageDriverTargetInfo> targetVisitor,
      @Nullable PayloadCodec codec,
      CancellationToken<CancellationException> cancellationToken) {
    CompletableFuture<Void> stored =
        storeAsync(builder, target, targetVisitor, cancellationToken, null);
    return encodeReferences
        ? stored.thenCompose(v -> ExternalStorageReferenceCodec.encode(builder, codec))
        : stored;
  }

  /**
   * Stores the payloads of {@code header}, then encodes the references that replace the fields
   * named by {@code codecKeys} with {@code codec}.
   *
   * @param codecKeys the fields that {@code codec} encoded, because their producer opted in
   */
  public void storeHeader(
      Header.Builder header,
      Set<String> codecKeys,
      @Nullable PayloadCodec codec,
      @Nullable StorageDriverTargetInfo target,
      CancellationToken<CancellationException> cancellationToken) {
    store(header, target, null, cancellationToken);
    if (encodeReferences) {
      ExternalStorageReferenceCodec.encode(header, codecKeys, codec);
    }
  }

  public void store(
      Message.Builder builder,
      @Nullable StorageDriverTargetInfo target,
      @Nullable MessageVisitor<StorageDriverTargetInfo> targetVisitor,
      CancellationToken<CancellationException> cancellationToken,
      @Nullable StorageOperationMetrics metrics) {
    getOrThrowIfCancelled(
        storeAsync(builder, target, targetVisitor, cancellationToken, metrics), cancellationToken);
  }

  public CompletableFuture<Void> storeAsync(
      Message.Builder builder,
      @Nullable StorageDriverTargetInfo target,
      @Nullable MessageVisitor<StorageDriverTargetInfo> targetVisitor,
      CancellationToken<CancellationException> cancellationToken) {
    return storeAsync(builder, target, targetVisitor, cancellationToken, null);
  }

  public CompletableFuture<Void> storeAsync(
      Message.Builder builder,
      @Nullable StorageDriverTargetInfo target,
      @Nullable MessageVisitor<StorageDriverTargetInfo> targetVisitor,
      CancellationToken<CancellationException> cancellationToken,
      @Nullable StorageOperationMetrics metrics) {
    return PayloadVisitors.visit(
        builder, storeOptions(target, targetVisitor, cancellationToken, metrics));
  }

  /**
   * Retrieves the plain references in {@code message}, leaving any reference encoded with the
   * payload codec in place.
   */
  public <T extends Message> T retrieve(
      T message, CancellationToken<CancellationException> cancellationToken) {
    return retrieve(message, cancellationToken, null);
  }

  /**
   * Decodes the references in {@code message} that were encoded with the payload codec, then
   * retrieves every reference.
   *
   * @param codec the codec to decode the payloads of {@code message} with, or null if there is none
   */
  public <T extends Message> T retrieve(
      T message,
      @Nullable PayloadCodec codec,
      CancellationToken<CancellationException> cancellationToken) {
    Message.Builder builder = message.toBuilder();
    if (!getOrThrowIfCancelled(
        ExternalStorageReferenceCodec.decode(builder, codec), cancellationToken)) {
      return message;
    }
    @SuppressWarnings("unchecked")
    T decoded = (T) builder.build();
    return retrieve(decoded, cancellationToken);
  }

  public <T extends Message> T retrieve(
      T message,
      CancellationToken<CancellationException> cancellationToken,
      @Nullable StorageOperationMetrics metrics) {
    return getOrThrowIfCancelled(
        retrieveAsync(message, cancellationToken, metrics), cancellationToken);
  }

  public <T extends Message> CompletableFuture<T> retrieveAsync(
      T message,
      CancellationToken<CancellationException> cancellationToken,
      @Nullable StorageOperationMetrics metrics) {
    return PayloadVisitors.visit(message, retrieveOptions(cancellationToken, metrics));
  }

  /**
   * Throws {@link ExternalStorageNotConfiguredException} if {@code message} contains any reference
   * payload, encoded or not. Used at inbound task boundaries when external storage is not
   * configured.
   */
  public static void throwIfContainsReference(Message message) {
    PayloadVisitorOptions<Void> options =
        PayloadVisitorOptions.<Void>newBuilder(
                (context, payloads) -> {
                  for (Payload payload : payloads) {
                    if (ExternalStorageReferences.isAnyReference(payload)) {
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
      @Nullable StorageOperationMetrics metrics) {
    return PayloadVisitorOptions.<StorageDriverTargetInfo>newBuilder(
            (visitedTarget, payloads) ->
                payloadTransformer.store(payloads, visitedTarget, cancellationToken, metrics))
        .setInitialContext(target)
        .setMessageVisitor(targetVisitor)
        .setConcurrency(payloadVisitConcurrency)
        .setSkipSearchAttributes(true)
        .build();
  }

  private PayloadVisitorOptions<Void> retrieveOptions(
      CancellationToken<CancellationException> cancellationToken,
      @Nullable StorageOperationMetrics metrics) {
    return PayloadVisitorOptions.<Void>newBuilder(
            (context, payloads) ->
                payloadTransformer.retrieve(payloads, cancellationToken, metrics))
        .setConcurrency(payloadVisitConcurrency)
        .setSkipSearchAttributes(true)
        .build();
  }
}
