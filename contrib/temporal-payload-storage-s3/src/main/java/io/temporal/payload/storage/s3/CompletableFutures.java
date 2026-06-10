package io.temporal.payload.storage.s3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class CompletableFutures {
  private CompletableFutures() {}

  /**
   * Returns a future that completes when all of the given futures complete, yielding a list of
   * their results. If any future completes exceptionally, the returned future also completes
   * exceptionally with the same exception. If the input list is empty, the returned future completes
   * immediately with an empty list.
   *
   * @param <T>
   * @param futures
   * @return
   */
  static <T> CompletableFuture<List<T>> allOf(List<CompletableFuture<T>> futures) {
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
        .thenApply(
            ignored -> {
              List<T> results = new ArrayList<>(futures.size());
              for (CompletableFuture<T> future : futures) {
                results.add(future.join());
              }
              return results;
            });
  }
}
