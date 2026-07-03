package io.temporal.internal.concurrent.structured;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class TaskScopeTest {

  @Test
  public void awaitAllFailsFastAndCancelsSiblings() {
    try (DefaultTaskScope<Integer> scope = new DefaultTaskScope<>()) {
      CompletableFuture<Integer> slow = new CompletableFuture<>();
      CompletableFuture<Integer> failing = new CompletableFuture<>();
      scope.attach(slow);
      scope.attach(failing);

      CompletableFuture<List<Integer>> out = scope.awaitAll();
      failing.completeExceptionally(new IllegalStateException("boom"));

      try {
        out.join();
        fail("Expected awaitAll to fail fast");
      } catch (CompletionException e) {
        assertTrue(e.getCause() instanceof IllegalStateException);
      }
      assertTrue(slow.isCancelled());
    }
  }

  @Test
  public void awaitAllSettledWaitsForAllEvenWhenSomeFail() {
    try (DefaultTaskScope<Integer> scope = new DefaultTaskScope<>()) {
      CompletableFuture<Integer> failing = new CompletableFuture<>();
      CompletableFuture<Integer> slow = new CompletableFuture<>();
      scope.attach(failing);
      scope.attach(slow);

      CompletableFuture<List<Result<Integer>>> out = scope.awaitAllSettled();
      failing.completeExceptionally(new IllegalStateException("boom"));

      // Must keep waiting for the still-pending sibling.
      assertFalse(out.isDone());

      slow.complete(1);

      List<Result<Integer>> results = out.join();
      assertTrue(results.get(0).isFailure());
      assertTrue(results.get(1).isSuccess());
    }
  }

  @Test
  public void awaitAllWaitsForTaskDerivedWhileWaiting() {
    try (DefaultTaskScope<Integer> scope = new DefaultTaskScope<>()) {
      CompletableFuture<Integer> root = new CompletableFuture<>();
      TaskChain<Integer> rootTask = scope.attach(root);

      CompletableFuture<List<Integer>> out = scope.awaitAll();
      assertFalse(out.isDone());

      // Derive a stage after awaitAll has already snapshotted the initial task set.
      rootTask.map(value -> value + 1);
      root.complete(1);

      assertEquals(Arrays.asList(2), out.join());
    }
  }

  @Test
  public void cancellingReturnedFutureCancelsScope() {
    try (DefaultTaskScope<Integer> scope = new DefaultTaskScope<>()) {
      CompletableFuture<Integer> pending = new CompletableFuture<>();
      scope.attach(pending);

      CompletableFuture<?> awaiting = scope.awaitAll();
      awaiting.cancel(true);

      // Cancelling the future returned by awaitAll cancels the whole scope.
      assertTrue(scope.token().isCancellationRequested());
      assertTrue(pending.isCancelled());
    }
  }

  @Test
  public void attachAfterCloseThrows() {
    DefaultTaskScope<Integer> scope = new DefaultTaskScope<>();
    scope.close();

    try {
      scope.attach(new CompletableFuture<>());
      fail("Expected attach on a closed scope to throw");
    } catch (IllegalStateException expected) {
      // expected
    }
  }

  @Test
  public void withScopeCompletesWithTransformedResult() throws Exception {
    CompletableFuture<Integer> first = new CompletableFuture<>();
    CompletableFuture<Integer> second = new CompletableFuture<>();

    CompletableFuture<Integer> result =
        TaskScope.<Integer, Integer>withScope(
            scope -> {
              scope.attach(first);
              scope.attach(second);
              return scope.awaitAll(values -> values.get(0) + values.get(1));
            });

    first.complete(2);
    second.complete(3);

    assertEquals(5, result.get(2, TimeUnit.SECONDS).intValue());
  }

  @Test
  public void withScopeFailsFastAndSettlesAfterAllTasksSettle() throws Exception {
    CompletableFuture<Integer> failing = new CompletableFuture<>();
    CompletableFuture<Integer> sibling = new CompletableFuture<>();

    CompletableFuture<Integer> result =
        TaskScope.<Integer, Integer>withScope(
            scope -> {
              scope.attach(failing);
              scope.attach(sibling);
              return scope.awaitAll(() -> 0);
            });

    failing.completeExceptionally(new IllegalStateException("boom"));

    try {
      result.get(2, TimeUnit.SECONDS);
      fail("Expected the failure to propagate");
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof IllegalStateException);
    }
    // Fail-fast cancels the sibling; the scope's result only settles once it has.
    assertTrue(sibling.isCancelled());
  }

  @Test
  public void withScopeBodyThrowingSynchronouslyCompletesFutureExceptionally() throws Exception {
    CompletableFuture<Integer> result =
        TaskScope.<Object, Integer>withScope(
            scope -> {
              throw new IllegalStateException("boom");
            });

    try {
      result.get(2, TimeUnit.SECONDS);
      fail("Expected the body failure to propagate");
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof IllegalStateException);
    }
  }
}
