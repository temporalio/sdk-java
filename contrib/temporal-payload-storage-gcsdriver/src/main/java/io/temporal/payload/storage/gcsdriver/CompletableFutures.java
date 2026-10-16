package io.temporal.payload.storage.gcsdriver;

import io.temporal.common.CancellationToken;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Function;

final class CompletableFutures {
  private CompletableFutures() {}

  /** Runs a batch in input order, failing fast and cancelling outstanding work on any failure. */
  static <I, O> CompletableFuture<List<O>> mapAsync(
      List<I> inputs,
      CancellationToken<CancellationException> token,
      Function<I, CompletableFuture<O>> operation) {
    if (inputs.isEmpty()) {
      return CompletableFuture.completedFuture(Collections.emptyList());
    }
    CompletableFuture<List<O>> result = new CompletableFuture<>();
    ConcurrentLinkedQueue<CompletableFuture<O>> requests = new ConcurrentLinkedQueue<>();
    result.whenComplete(
        (value, error) -> {
          if (error != null) {
            for (CompletableFuture<O> request : requests) {
              request.cancel(true);
            }
          }
        });
    CancellationToken.Registration registration = token.onCancel(() -> result.cancel(true));
    result.whenComplete((value, error) -> registration.close());
    AtomicReferenceArray<O> values = new AtomicReferenceArray<>(inputs.size());
    AtomicInteger remaining = new AtomicInteger(inputs.size());
    for (int i = 0; i < inputs.size() && !result.isDone(); i++) {
      final int index = i;
      CompletableFuture<O> request;
      try {
        request = operation.apply(inputs.get(i));
      } catch (RuntimeException e) {
        result.completeExceptionally(e);
        break;
      }
      requests.add(request);
      // Cancellation can race with the operation returning its future.
      if (result.isDone()) {
        request.cancel(true);
      }
      request.whenComplete(
          (value, error) -> {
            if (error != null) {
              result.completeExceptionally(unwrap(error));
            } else {
              values.set(index, value);
              if (remaining.decrementAndGet() == 0) {
                List<O> ordered = new ArrayList<>(inputs.size());
                for (int j = 0; j < inputs.size(); j++) {
                  ordered.add(values.get(j));
                }
                result.complete(ordered);
              }
            }
          });
    }
    return result;
  }

  static Throwable unwrap(Throwable error) {
    while ((error instanceof CompletionException || error instanceof ExecutionException)
        && error.getCause() != null) {
      error = error.getCause();
    }
    return error;
  }

  static <T> CompletableFuture<T> failedFuture(Throwable error) {
    CompletableFuture<T> result = new CompletableFuture<>();
    result.completeExceptionally(error);
    return result;
  }
}
