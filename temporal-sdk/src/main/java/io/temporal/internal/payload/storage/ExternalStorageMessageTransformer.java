package io.temporal.internal.payload.storage;

import com.google.common.base.Throwables;
import com.google.protobuf.Message;
import io.temporal.common.CancellationToken;
import io.temporal.internal.concurrent.structured.CancelSource;
import io.temporal.internal.payload.visitor.PayloadVisitorOptions;
import io.temporal.internal.payload.visitor.PayloadVisitors;
import io.temporal.payload.storage.ExternalStorageOptions;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
public final class ExternalStorageMessageTransformer {
  private final ExternalStoragePayloadTransformer payloadTransformer;
  private final int payloadVisitConcurrency;

  public static ExternalStorageMessageTransformer create(ExternalStorageOptions options) {
    return create(options, 1);
  }

  public static ExternalStorageMessageTransformer create(
      ExternalStorageOptions options, int payloadVisitConcurrency) {
    return new ExternalStorageMessageTransformer(
        ExternalStoragePayloadTransformer.fromOptions(options), payloadVisitConcurrency);
  }

  ExternalStorageMessageTransformer(
      ExternalStoragePayloadTransformer payloadTransformer, int payloadVisitConcurrency) {
    this.payloadTransformer = payloadTransformer;
    this.payloadVisitConcurrency = payloadVisitConcurrency;
  }

  public <T extends Message> T storeBlocking(T message, @Nullable StorageDriverTargetInfo target) {
    return storeBlocking(message, target, null);
  }

  public <T extends Message> T storeBlocking(
      T message, @Nullable StorageDriverTargetInfo target, @Nullable Duration timeout) {
    CancelSource<CancellationException> cancel = new CancelSource<>(CancellationException::new);
    return getOrCancel(store(message, target, cancel.token()), cancel, timeout);
  }

  public <T extends Message> T retrieveBlocking(T message) {
    CancelSource<CancellationException> cancel = new CancelSource<>(CancellationException::new);
    return getOrCancel(retrieve(message, cancel.token()), cancel, null);
  }

  public <T extends Message> CompletableFuture<T> retrieveAsync(T message) {
    return retrieve(message, CancellationToken.none());
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
