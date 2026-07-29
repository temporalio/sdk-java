package io.temporal.internal.payload.storage;

import com.google.protobuf.Message;
import io.temporal.internal.payload.visitor.PayloadVisitor;
import io.temporal.internal.payload.visitor.PayloadVisitorOptions;
import io.temporal.internal.payload.visitor.PayloadVisitors;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;

/**
 * Converts payload lists reachable from a proto message by delegating each visited list to {@link
 * ExternalStoragePayloadConverter}.
 *
 * <p>Search attributes stay inline because the server indexes and validates their payload values.
 */
final class ExternalStorageMessageConverter {
  private final ExternalStoragePayloadConverter payloadConverter;
  private final int payloadVisitConcurrency;

  ExternalStorageMessageConverter(
      ExternalStoragePayloadConverter payloadConverter, int payloadVisitConcurrency) {
    this.payloadConverter = payloadConverter;
    this.payloadVisitConcurrency = payloadVisitConcurrency;
  }

  <T extends Message> CompletableFuture<T> store(
      T message, @Nullable StorageDriverTargetInfo target) {
    PayloadVisitorOptions<StorageDriverTargetInfo> options =
        PayloadVisitorOptions.<StorageDriverTargetInfo>newBuilder(
                (context, payloads) -> payloadConverter.store(context, payloads))
            .setInitialContext(target)
            .setConcurrency(payloadVisitConcurrency)
            .setSkipSearchAttributes(true)
            .build();
    return PayloadVisitors.visit(message, options);
  }

  <T extends Message> CompletableFuture<T> retrieve(T message) {
    PayloadVisitorOptions<Object> options =
        PayloadVisitorOptions.newBuilder(
                (PayloadVisitor<Object>) (context, payloads) -> payloadConverter.retrieve(payloads))
            .setConcurrency(payloadVisitConcurrency)
            .setSkipSearchAttributes(true)
            .build();
    return PayloadVisitors.visit(message, options);
  }
}
