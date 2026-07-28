package io.temporal.internal.concurrent.structured;

import io.temporal.common.CancellationToken;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nonnull;

/**
 * Reference implementation of {@link TaskScope}. Each task's {@link CancelSource} is linked to the
 * scope's token, so {@link #cancelAll()} trips every task at once.
 */
final class DefaultTaskScope<T> implements TaskScope<T> {

  private final CancelSource<CancellationException> scope =
      new CancelSource<>(CancellationException::new);
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final List<DefaultAsyncTask<?>> ownedTasks = new CopyOnWriteArrayList<>();
  private final List<DefaultAsyncTask<?>> resultTasks = new CopyOnWriteArrayList<>();

  @Override
  public CancellationToken<CancellationException> token() {
    return scope.token();
  }

  @Override
  public <U> AsyncTask<U> attach(@Nonnull CompletableFuture<U> future) {
    Objects.requireNonNull(future, "future");
    ensureOpen();
    CancelSource<CancellationException> childSrc =
        CancelSource.linkedTo(CancellationException::new, scope.token());
    DefaultAsyncTask<U> task =
        new DefaultAsyncTask<>(
            future, childSrc, () -> future.cancel(true), ownedTasks::add, this::replaceResultTask);
    ownedTasks.add(task);
    resultTasks.add(task);
    return task;
  }

  private void replaceResultTask(DefaultAsyncTask<?> parent, DefaultAsyncTask<?> child) {
    int index = resultTasks.indexOf(parent);
    if (index >= 0) {
      resultTasks.set(index, child);
    } else {
      resultTasks.add(child);
    }
  }

  private void ensureOpen() {
    if (closed.get()) {
      throw new IllegalStateException("TaskScope is closed");
    }
  }

  @Override
  public void cancelAll() {
    scope.cancel();
  }

  @Override
  public <R> CompletableFuture<R> awaitAll(Function<List<T>, R> resultTransformer) {
    CompletableFuture<R> result = new CompletableFuture<>();
    AtomicReference<Throwable> errorRef = new AtomicReference<>();
    awaitTermination(0, failFastWatch(errorRef))
        .whenComplete(
            (ignored, terminationError) -> {
              Throwable error = errorRef.get();
              if (error != null) {
                result.completeExceptionally(error);
                return;
              }
              try {
                List<DefaultAsyncTask<T>> collected = resultTaskSnapshot();
                List<T> values = new ArrayList<>(collected.size());
                for (DefaultAsyncTask<T> task : collected) {
                  values.add(task.join());
                }
                result.complete(resultTransformer.apply(values));
              } catch (Throwable t) {
                result.completeExceptionally(t);
              }
            });
    propagateCancellation(result);
    return result;
  }

  @Override
  public CompletableFuture<List<Result<T>>> awaitAllSettled() {
    CompletableFuture<List<Result<T>>> result = new CompletableFuture<>();
    // All-settled never fails fast, so it collects outcomes without cancelling siblings.
    awaitTermination(0, null)
        .whenComplete(
            (ignored, terminationError) -> {
              List<DefaultAsyncTask<T>> collected = resultTaskSnapshot();
              List<Result<T>> results = new ArrayList<>(collected.size());
              for (DefaultAsyncTask<T> task : collected) {
                results.add(task.joinSettled());
              }
              result.complete(results);
            });
    propagateCancellation(result);
    return result;
  }

  /**
   * Completes once every owned task has settled. The owned-task list grows as tasks are derived, so
   * each round re-checks and waits for any that appeared meanwhile. Never completes exceptionally.
   * {@code onDiscover} runs once per task as it is first observed.
   */
  private CompletableFuture<Void> awaitTermination(
      int from, Consumer<DefaultAsyncTask<?>> onDiscover) {
    List<DefaultAsyncTask<?>> snapshot = new ArrayList<>(ownedTasks);
    if (from >= snapshot.size()) {
      return CompletableFuture.completedFuture(null);
    }
    CompletableFuture<?>[] terminations = new CompletableFuture<?>[snapshot.size() - from];
    for (int i = from; i < snapshot.size(); i++) {
      DefaultAsyncTask<?> task = snapshot.get(i);
      if (onDiscover != null) {
        onDiscover.accept(task);
      }
      terminations[i - from] = task.terminated();
    }
    int next = snapshot.size();
    return CompletableFuture.allOf(terminations)
        .thenCompose(ignored -> awaitTermination(next, onDiscover));
  }

  /**
   * Records the first failure and cancels the whole sibling group. Registered on each task as it is
   * discovered, so tasks derived mid-wait fail fast too.
   */
  private Consumer<DefaultAsyncTask<?>> failFastWatch(AtomicReference<Throwable> errorRef) {
    return task ->
        task.toCompletableFuture()
            .whenComplete(
                (ignored, error) -> {
                  if (error != null
                      && errorRef.compareAndSet(null, DefaultAsyncTask.unwrap(error))) {
                    cancelAll();
                  }
                });
  }

  private void propagateCancellation(CompletableFuture<?> result) {
    result.whenComplete(
        (ignored, error) -> {
          if (result.isCancelled()) {
            cancelAll();
          }
        });
  }

  @SuppressWarnings("unchecked")
  private List<DefaultAsyncTask<T>> resultTaskSnapshot() {
    return (List<DefaultAsyncTask<T>>) (List<?>) new ArrayList<>(resultTasks);
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    // Cancelling settles every attached and derived future synchronously; there are no threads to
    // wait for.
    cancelAll();
  }

  /**
   * Non-blocking completion for {@link TaskScope#withScope}: when {@code body} settles, cancels the
   * group on failure, waits for every task to settle, then delivers the outcome. The returned
   * future does not settle until all tasks have, so no task outlives the scope.
   */
  <R> CompletableFuture<R> closeWhenDone(CompletableFuture<R> body) {
    CompletableFuture<R> delivered = new CompletableFuture<>();
    body.whenComplete(
        (value, error) -> {
          if (error != null) {
            cancelAll();
          }
          allTerminated()
              .whenComplete(
                  (ignored, terminationError) -> {
                    closed.set(true);
                    if (error != null) {
                      delivered.completeExceptionally(DefaultAsyncTask.unwrap(error));
                    } else {
                      delivered.complete(value);
                    }
                  });
        });
    propagateCancellation(delivered);
    return delivered;
  }

  private CompletableFuture<Void> allTerminated() {
    return awaitTermination(0, null);
  }

  /**
   * Runs {@code body} against {@code scope} and ties the scope's lifetime to the returned future.
   */
  static <T, R> CompletableFuture<R> run(
      DefaultTaskScope<T> scope, Function<TaskScope<T>, CompletableFuture<R>> body) {
    CompletableFuture<R> future;
    try {
      future = Objects.requireNonNull(body.apply(scope), "future");
    } catch (Throwable t) {
      scope.close();
      CompletableFuture<R> failed = new CompletableFuture<>();
      failed.completeExceptionally(t);
      return failed;
    }
    return scope.closeWhenDone(future);
  }
}
