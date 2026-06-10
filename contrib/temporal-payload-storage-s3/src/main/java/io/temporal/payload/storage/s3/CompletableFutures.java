package io.temporal.payload.storage.s3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class CompletableFutures {
  private CompletableFutures() {}

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
