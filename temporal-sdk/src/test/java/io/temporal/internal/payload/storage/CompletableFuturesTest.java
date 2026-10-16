package io.temporal.internal.payload.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.temporal.common.CancellationToken;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class CompletableFuturesTest {
  @Test
  public void emptyBatchCompletesImmediately() {
    assertEquals(
        Collections.emptyList(), CompletableFutures.allAsList(Collections.emptyList()).join());
  }

  @Test(timeout = 5000)
  public void collectsOutOfOrderCompletionsInInputOrderIncludingNulls() {
    CompletableFuture<String> first = new CompletableFuture<>();
    CompletableFuture<String> second = new CompletableFuture<>();
    CompletableFuture<List<String>> result =
        CompletableFutures.allAsList(Arrays.asList(first, second));
    second.complete(null);
    assertFalse(result.isDone());
    first.complete("first");
    assertEquals(Arrays.asList("first", null), result.join());
  }

  @Test(timeout = 5000)
  public void alreadyFailedInputCancelsPendingWorkAndUnwrapsCause() {
    RuntimeException cause = new RuntimeException("failed");
    CompletableFuture<String> pending = new CompletableFuture<>();
    CompletableFuture<String> failed =
        CompletableFutures.failedFuture(new CompletionException(new ExecutionException(cause)));
    CompletableFuture<List<String>> result =
        CompletableFutures.allAsList(Arrays.asList(failed, pending));
    assertSame(cause, assertThrows(CompletionException.class, result::join).getCause());
    assertTrue(pending.isCancelled());
  }

  @Test(timeout = 5000)
  public void firstFailureCancelsPendingWorkAndRemainsTheBatchCause() {
    CompletableFuture<String> first = new CompletableFuture<>();
    CompletableFuture<String> second = new CompletableFuture<>();
    CompletableFuture<List<String>> result =
        CompletableFutures.allAsList(Arrays.asList(first, second));
    RuntimeException cause = new RuntimeException("first failure");
    second.completeExceptionally(cause);
    assertSame(cause, assertThrows(CompletionException.class, result::join).getCause());
    assertTrue(first.isCancelled());
  }

  @Test
  public void cancellingBatchCancelsAllInputs() {
    CompletableFuture<String> first = new CompletableFuture<>();
    CompletableFuture<String> second = new CompletableFuture<>();
    CompletableFuture<List<String>> result =
        CompletableFutures.allAsList(Arrays.asList(first, second));
    result.cancel(true);
    assertTrue(first.isCancelled());
    assertTrue(second.isCancelled());
  }

  @Test(timeout = 5000)
  public void submissionFailureCancelsEarlierRequestsAndStopsLaterSubmissions() {
    CompletableFuture<String> pending = new CompletableFuture<>();
    AtomicInteger started = new AtomicInteger();
    RuntimeException cause = new RuntimeException("submission failed");
    CompletableFuture<List<String>> result =
        CompletableFutures.mapAsync(
            Arrays.asList(1, 2, 3),
            CancellationToken.none(),
            input -> {
              started.incrementAndGet();
              if (input == 2) {
                throw cause;
              }
              return pending;
            });
    assertSame(cause, assertThrows(CompletionException.class, result::join).getCause());
    assertEquals(2, started.get());
    assertTrue(pending.isCancelled());
  }
}
