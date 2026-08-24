package io.temporal.internal.payload.storage;

import com.google.protobuf.Message;
import io.temporal.common.CancellationToken;
import io.temporal.internal.payload.visitor.PayloadVisitorOptions;
import io.temporal.internal.payload.visitor.PayloadVisitors;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;

/**
 * Transforms payload lists reachable from a proto message by delegating each visited list to {@link
 * ExternalStoragePayloadTransformer}.
 *
 * <p>Search attributes stay inline because the server indexes and validates their payload values.
 *
 * <p>The {@link Message.Builder} overloads transform in place; the {@link Message} overloads copy
 * through a builder and complete with the copy.
 */
final class ExternalStorageMessageTransformer {
  private final ExternalStoragePayloadTransformer payloadTransformer;
  private final int payloadVisitConcurrency;

  ExternalStorageMessageTransformer(
      ExternalStoragePayloadTransformer payloadTransformer, int payloadVisitConcurrency) {
    this.payloadTransformer = payloadTransformer;
    this.payloadVisitConcurrency = payloadVisitConcurrency;
  }

  <T extends Message> CompletableFuture<T> store(
      T message,
      @Nullable StorageDriverTargetInfo target,
      CancellationToken<CancellationException> cancellationToken) {
    return PayloadVisitors.visit(message, storeOptions(target, cancellationToken));
  }

  CompletableFuture<Void> store(
      Message.Builder builder,
      @Nullable StorageDriverTargetInfo target,
      CancellationToken<CancellationException> cancellationToken) {
    return PayloadVisitors.visit(builder, storeOptions(target, cancellationToken));
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
      CancellationToken<CancellationException> cancellationToken) {
    return PayloadVisitorOptions.<StorageDriverTargetInfo>newBuilder(
            (visitedTarget, payloads) ->
                payloadTransformer.store(payloads, visitedTarget, cancellationToken))
        .setInitialContext(target)
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
