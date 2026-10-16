package io.temporal.internal.payload.storage;

import io.temporal.common.CancellationToken;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Future coordination shared by external storage drivers. */
public final class CompletableFutures {
  private CompletableFutures() {}

  /**
   * Completes with the results in input order once every future succeeds. Fails fast with the first
   * failure's cause as soon as any future fails, without waiting for the rest.
   */
  public static <T> CompletableFuture<List<T>> allAsList(List<CompletableFuture<T>> futures) {
    CompletableFuture<List<T>> result = new CompletableFuture<>();
    if (futures.isEmpty()) {
      result.complete(new ArrayList<>());
      return result;
    }
    AtomicInteger remaining = new AtomicInteger(futures.size());
    for (CompletableFuture<T> future : futures) {
      future.whenComplete(
          (value, ex) -> {
            if (ex != null) {
              result.completeExceptionally(unwrap(ex));
            } else if (remaining.decrementAndGet() == 0) {
              List<T> results = new ArrayList<>(futures.size());
              for (CompletableFuture<T> completed : futures) {
                results.add(completed.join());
              }
              result.complete(results);
            }
          });
    }
    result.whenComplete(
        (value, ex) -> {
          if (ex != null) {
            for (CompletableFuture<T> future : futures) {
              future.cancel(true);
            }
          }
        });
    return result;
  }

  /**
   * Starts operations until the batch fails or is cancelled, collecting results in input order.
   * Batch failure, batch cancellation, and token cancellation cancel outstanding operations.
   */
  public static <I, O> CompletableFuture<List<O>> mapAsync(
      List<I> inputs,
      CancellationToken<CancellationException> token,
      Function<I, CompletableFuture<O>> operation) {
    List<CompletableFuture<O>> outputs = new ArrayList<>(inputs.size());
    for (int i = 0; i < inputs.size(); i++) {
      outputs.add(new CompletableFuture<>());
    }
    CompletableFuture<List<O>> result = allAsList(outputs);
    if (inputs.isEmpty()) {
      return result;
    }
    CancellationToken.Registration registration = token.onCancel(() -> result.cancel(true));
    result.whenComplete((value, error) -> registration.close());
    for (int i = 0; i < inputs.size() && !result.isDone(); i++) {
      CompletableFuture<O> output = outputs.get(i);
      CompletableFuture<O> request;
      try {
        request = operation.apply(inputs.get(i));
      } catch (RuntimeException e) {
        output.completeExceptionally(e);
        break;
      }
      // Registering on an already-cancelled output also handles cancellation during submission.
      output.whenComplete(
          (value, error) -> {
            if (output.isCancelled()) {
              request.cancel(true);
            }
          });
      request.whenComplete(
          (value, error) -> {
            if (error == null) {
              output.complete(value);
            } else {
              output.completeExceptionally(unwrap(error));
            }
          });
    }
    return result;
  }

  /** Returns the underlying cause of completion and execution exception wrappers. */
  public static Throwable unwrap(Throwable t) {
    while ((t instanceof CompletionException || t instanceof ExecutionException)
        && t.getCause() != null) {
      t = t.getCause();
    }
    return t;
  }

  /** Returns an exceptionally completed future, including when running on Java 8. */
  public static <T> CompletableFuture<T> failedFuture(Throwable error) {
    CompletableFuture<T> result = new CompletableFuture<>();
    result.completeExceptionally(error);
    return result;
  }
}
