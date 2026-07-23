package io.temporal.internal.concurrent.structured;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.Test;

public class TaskScopeAndResultTest {

  @Test
  public void awaitAllAndAwaitAllSettledHandleEmptyScopes() {
    try (TaskScope<Integer> scope = new DefaultTaskScope<>()) {
      assertTrue(scope.awaitAll().join().isEmpty());
      assertTrue(scope.awaitAllSettled().join().isEmpty());
    }
  }

  @Test
  public void awaitAllReturnsValuesInAttachOrder() {
    try (DefaultTaskScope<Integer> scope = new DefaultTaskScope<>()) {
      CompletableFuture<Integer> first = new CompletableFuture<>();
      CompletableFuture<Integer> second = new CompletableFuture<>();
      scope.attach(first);
      scope.attach(second);

      CompletableFuture<List<Integer>> out = scope.awaitAll();

      // Completion order does not affect collection order.
      second.complete(2);
      first.complete(1);

      assertEquals(Arrays.asList(1, 2), out.join());
    }
  }

  @Test
  public void awaitAllSettledPreservesPerTaskOutcome() {
    try (DefaultTaskScope<Integer> scope = new DefaultTaskScope<>()) {
      CompletableFuture<Integer> ok = new CompletableFuture<>();
      CompletableFuture<Integer> bad = new CompletableFuture<>();
      CompletableFuture<Integer> cancelled = new CompletableFuture<>();
      scope.attach(ok);
      scope.attach(bad);
      scope.attach(cancelled);

      ok.complete(1);
      bad.completeExceptionally(new IllegalArgumentException("bad"));
      cancelled.cancel(true);

      List<Result<Integer>> results = scope.awaitAllSettled().join();

      assertTrue(results.get(0).isSuccess());
      assertTrue(results.get(1).isFailure());
      assertTrue(results.get(2).isCancelled());
    }
  }

  @Test
  public void resultHelpersBehaveAcrossStates() {
    Result<Integer> success = Result.success(3);
    Result<Integer> failure = Result.failure(new IllegalArgumentException("bad"));
    Result<Integer> cancelled = Result.cancelled();

    assertEquals(4, success.map(value -> value + 1).get().intValue());
    assertEquals(9, success.fold(value -> value * 3, error -> -1).intValue());
    assertEquals(7, failure.orElse(7).intValue());
    assertEquals(8, cancelled.orElse(8).intValue());
    assertFalse(failure.map(value -> value + 1).isSuccess());
    assertEquals("cancelled", cancelled.fold(value -> "success", error -> "cancelled"));
  }

  @Test
  public void awaitAllAppliesTransformerAcrossResults() {
    try (DefaultTaskScope<Integer> scope = new DefaultTaskScope<>()) {
      CompletableFuture<Integer> first = new CompletableFuture<>();
      CompletableFuture<Integer> second = new CompletableFuture<>();
      scope.attach(first);
      scope.attach(second);

      CompletableFuture<Integer> sum = scope.awaitAll(values -> values.get(0) + values.get(1));

      first.complete(2);
      second.complete(3);

      assertEquals(5, sum.join().intValue());
    }
  }

  @Test
  public void awaitAllCollectsTransformedChildInsteadOfParent() {
    try (DefaultTaskScope<Integer> scope = new DefaultTaskScope<>()) {
      CompletableFuture<Integer> upstream = new CompletableFuture<>();
      scope.attach(upstream).map(value -> value * 10);

      upstream.complete(1);

      // The transformed child replaces its parent in the collected results.
      assertEquals(Arrays.asList(10), scope.awaitAll().join());
    }
  }

  @Test
  public void awaitAllSettledDoesNotCancelSiblingsOnFailure() {
    try (DefaultTaskScope<Integer> scope = new DefaultTaskScope<>()) {
      CompletableFuture<Integer> good = new CompletableFuture<>();
      CompletableFuture<Integer> bad = new CompletableFuture<>();
      AsyncTask<Integer> goodTask = scope.attach(good);
      scope.attach(bad);

      CompletableFuture<List<Result<Integer>>> out = scope.awaitAllSettled();
      bad.completeExceptionally(new IllegalStateException("boom"));

      // All-settled never fails fast, so the still-pending sibling is not cancelled.
      assertFalse(goodTask.isCancelled());
      assertFalse(out.isDone());

      good.complete(1);

      List<Result<Integer>> results = out.join();
      assertTrue(results.get(0).isSuccess());
      assertEquals(Integer.valueOf(1), results.get(0).get());
      assertTrue(results.get(1).isFailure());
    }
  }

  @Test
  public void attachMapCollectsTransformedValue() {
    try (DefaultTaskScope<Integer> scope = new DefaultTaskScope<>()) {
      CompletableFuture<Integer> upstream = new CompletableFuture<>();
      scope.attach(upstream).map(value -> value + 100);

      upstream.complete(5);

      // Mirrors the production pattern: attach an external future, then transform it in-scope.
      assertEquals(Arrays.asList(105), scope.awaitAll().join());
    }
  }

  @Test
  public void awaitAllPropagatesTransformerException() {
    try (DefaultTaskScope<Integer> scope = new DefaultTaskScope<>()) {
      CompletableFuture<Integer> upstream = new CompletableFuture<>();
      scope.attach(upstream);
      upstream.complete(1);

      CompletableFuture<Integer> result =
          scope.awaitAll(
              values -> {
                throw new IllegalStateException("transform");
              });

      try {
        result.join();
        fail("Expected the transformer failure to propagate");
      } catch (CompletionException e) {
        assertTrue(e.getCause() instanceof IllegalStateException);
      }
    }
  }

  @Test
  public void largeFanOutCollectsAllResultsInOrder() {
    int taskCount = 200;
    try (DefaultTaskScope<Integer> scope = new DefaultTaskScope<>()) {
      for (int i = 0; i < taskCount; i++) {
        scope.attach(CompletableFuture.completedFuture(i));
      }

      List<Integer> results = scope.awaitAll().join();

      assertEquals(taskCount, results.size());
      for (int i = 0; i < taskCount; i++) {
        assertEquals(Integer.valueOf(i), results.get(i));
      }
    }
  }
}
