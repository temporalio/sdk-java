package io.temporal.internal.payload.storage;

import com.google.common.base.Throwables;
import com.google.protobuf.Message;
import io.temporal.internal.payload.visitor.PayloadVisitor;
import io.temporal.internal.payload.visitor.PayloadVisitorOptions;
import io.temporal.internal.payload.visitor.PayloadVisitors;
import io.temporal.payload.storage.ExternalStorageOptions;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.annotation.Nullable;

/**
 * Converts payload lists reachable from a proto message by delegating each visited list to {@link
 * ExternalStoragePayloadConverter}.
 *
 * <p>Search attributes stay inline because the server indexes and validates their payload values.
 */
public final class ExternalStorageMessageConverter {
  private final ExternalStoragePayloadConverter payloadConverter;
  private final int payloadVisitConcurrency;

  /**
   * Builds a message converter from user-facing options. {@code payloadVisitConcurrency} bounds the
   * number of concurrent payload-list visits within a single message walk (at least {@code 1}).
   */
  public static ExternalStorageMessageConverter create(
      ExternalStorageOptions options, int payloadVisitConcurrency) {
    return new ExternalStorageMessageConverter(
        ExternalStoragePayloadConverter.fromOptions(options), payloadVisitConcurrency);
  }

  ExternalStorageMessageConverter(
      ExternalStoragePayloadConverter payloadConverter, int payloadVisitConcurrency) {
    this.payloadConverter = payloadConverter;
    this.payloadVisitConcurrency = payloadVisitConcurrency;
  }

  public <T extends Message> CompletableFuture<T> store(
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

  public <T extends Message> CompletableFuture<T> retrieve(T message) {
    PayloadVisitorOptions<Object> options =
        PayloadVisitorOptions.newBuilder(
                (PayloadVisitor<Object>) (context, payloads) -> payloadConverter.retrieve(payloads))
            .setConcurrency(payloadVisitConcurrency)
            .setSkipSearchAttributes(true)
            .build();
    return PayloadVisitors.visit(message, options);
  }

  /** Blocking variant of {@link #store}, for synchronous worker call sites. */
  public <T extends Message> T storeBlocking(T message, @Nullable StorageDriverTargetInfo target) {
    return join(store(message, target));
  }

  /** Blocking variant of {@link #retrieve}, for synchronous worker call sites. */
  public <T extends Message> T retrieveBlocking(T message) {
    return join(retrieve(message));
  }

  private static <T> T join(CompletableFuture<T> future) {
    try {
      return future.join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause();
      if (cause != null) {
        Throwables.throwIfUnchecked(cause);
      }
      throw e;
    }
  }
}
