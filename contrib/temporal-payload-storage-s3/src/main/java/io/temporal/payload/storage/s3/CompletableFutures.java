package io.temporal.payload.storage.s3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

final class CompletableFutures {
  private CompletableFutures() {}

  /**
   * Completes with the results in input order once every future succeeds. Fails fast with the first
   * failure's cause as soon as any future fails, without waiting for the rest.
   */
  static <T> CompletableFuture<List<T>> allAsList(List<CompletableFuture<T>> futures) {
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

  static Throwable unwrap(Throwable t) {
    while ((t instanceof CompletionException || t instanceof ExecutionException)
        && t.getCause() != null) {
      t = t.getCause();
    }
    return t;
  }
}
