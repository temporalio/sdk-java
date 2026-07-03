package io.temporal.internal.concurrent.structured;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class AsyncTaskTest {

  @Test
  public void recoverDoesNotHandleCancellation() {
    DefaultTaskScope<Integer> scope = new DefaultTaskScope<>();

    CompletableFuture<Integer> upstream = new CompletableFuture<>();
    AsyncTask<Integer> task = scope.attach(upstream);
    AsyncTask<Integer> recovered = task.recover(error -> 99);

    task.cancel();

    assertTrue(task.joinSettled().isCancelled());
    assertTrue(recovered.joinSettled().isCancelled());
  }

  @Test
  public void cancelReturnsFalseWhenAlreadySettledAndPreservesValue() {
    DefaultTaskScope<Integer> scope = new DefaultTaskScope<>();

    CompletableFuture<Integer> upstream = new CompletableFuture<>();
    AsyncTask<Integer> task = scope.attach(upstream);
    upstream.complete(1);

    assertFalse(task.cancel());
    assertEquals(1, task.join().intValue());
    assertFalse(task.isCancelled());
  }

  @Test
  public void cancelPropagatesToUnsettledDerivedChild() {
    DefaultTaskScope<Integer> scope = new DefaultTaskScope<>();

    CompletableFuture<Integer> upstream = new CompletableFuture<>();
    AsyncTask<Integer> parent = scope.attach(upstream);
    AsyncTask<Integer> child = parent.map(value -> value + 1);

    parent.cancel();

    assertTrue(parent.joinSettled().isCancelled());
    assertTrue(child.joinSettled().isCancelled());
  }

  @Test
  public void mapRecoverChainTransformsFailureIntoSuccess() {
    DefaultTaskScope<Integer> scope = new DefaultTaskScope<>();

    CompletableFuture<Integer> upstream = new CompletableFuture<>();
    AsyncTask<Integer> task =
        scope.attach(upstream).map(value -> value / 0).recover(error -> 7).map(value -> value * 2);

    upstream.complete(1);

    assertEquals(14, task.join().intValue());
  }

  @Test
  public void whenSettledReceivesUnwrappedFailure() {
    DefaultTaskScope<Integer> scope = new DefaultTaskScope<>();

    IllegalStateException failure = new IllegalStateException("boom");
    AtomicReference<Throwable> seen = new AtomicReference<>();

    CompletableFuture<Integer> upstream = new CompletableFuture<>();
    AsyncTask<Integer> task = scope.attach(upstream);
    task.whenSettled((value, error) -> seen.set(error));

    upstream.completeExceptionally(new CompletionException(failure));

    assertSame(failure, seen.get());
    assertTrue(task.joinSettled().isFailure());
  }

  @Test
  public void whenSettledReceivesNullThrowableOnSuccess() {
    DefaultTaskScope<Integer> scope = new DefaultTaskScope<>();

    AtomicReference<Throwable> seen = new AtomicReference<>(new RuntimeException("sentinel"));

    CompletableFuture<Integer> upstream = new CompletableFuture<>();
    scope.attach(upstream).whenSettled((value, error) -> seen.set(error));

    upstream.complete(5);

    assertNull(seen.get());
  }

  @Test
  public void thenAcceptRunsSideEffectAndCompletesVoid() {
    DefaultTaskScope<Integer> scope = new DefaultTaskScope<>();

    AtomicReference<Integer> seen = new AtomicReference<>();
    CompletableFuture<Integer> upstream = new CompletableFuture<>();
    AsyncTask<Void> done = scope.attach(upstream).thenAccept(seen::set);

    upstream.complete(7);

    assertNull(done.join());
    assertEquals(Integer.valueOf(7), seen.get());
  }
}
