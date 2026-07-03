package io.temporal.internal.concurrent.structured;

import io.temporal.common.CancellationToken;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A structured-concurrency scope that owns a group of sibling tasks and a shared cancellation
 * signal, and guarantees that none of those tasks outlive the scope's completion.
 *
 * <pre>{@code
 * CompletableFuture<List<Data>> report = TaskScope.withScope(scope -> {
 *     scope.attach(fetchData(1));
 *     scope.attach(fetchData(2));
 *
 *     return scope.awaitAll();
 * });
 * }</pre>
 */
public interface TaskScope<T> extends AutoCloseable {

  /**
   * @return the scope-wide cancellation token (tripped by {@link #cancelAll()} or {@link
   *     #close()}).
   */
  CancellationToken token();

  /**
   * Attaches an existing asynchronous task to this scope. Scope cancellation requests cancellation
   * on the attached future.
   */
  <U> TaskChain<U> attach(CompletableFuture<U> future);

  /** Cancels every task in this scope. */
  void cancelAll();

  /**
   * Non-blocking fail-fast wait over all tasks currently owned by this scope.
   *
   * <p>The returned future completes with {@code resultSupplier.get()} after all tasks complete
   * successfully. On first failure or cancellation it completes exceptionally and cancels
   * unfinished tasks.
   *
   * <p>This method does not close the scope. Scope lifetime remains caller-owned.
   */
  <R> CompletableFuture<R> awaitAll(Supplier<R> resultSupplier);

  /**
   * Non-blocking fail-fast wait that completes with the collected task results in collection order.
   *
   * <p>This method does not close the scope. Scope lifetime remains caller-owned.
   */
  default CompletableFuture<List<T>> awaitAll() {
    return awaitAll(Function.identity());
  }

  /**
   * Non-blocking fail-fast wait that collects all task results and passes them to a transformer.
   *
   * <p>The returned future completes after all collected tasks complete successfully. Attached
   * tasks are collected by default; when a collected task is transformed, the transformed child
   * replaces its parent in the collected result list. On first failure or cancellation it completes
   * exceptionally and cancels unfinished tasks.
   *
   * <p>This method does not close the scope. Scope lifetime remains caller-owned.
   */
  <R> CompletableFuture<R> awaitAll(Function<List<T>, R> resultTransformer);

  /**
   * Non-blocking wait that completes with each collected task's settled outcome in collection
   * order.
   *
   * <p>Task failures and cancellations are returned as {@link Result} values instead of completing
   * the returned future exceptionally.
   *
   * <p>This method does not close the scope. Scope lifetime remains caller-owned.
   */
  CompletableFuture<List<Result<T>>> awaitAllSettled();

  /** Cancels all tasks; idempotent. Cancellation settles every attached and derived future. */
  @Override
  void close();

  /**
   * Runs async work in a lexical scope and closes the scope when the returned future settles. The
   * returned future does not complete until every task attached in {@code body} has settled, so no
   * task outlives the scope.
   */
  static <T, R> CompletableFuture<R> withScope(Function<TaskScope<T>, CompletableFuture<R>> body) {
    return DefaultTaskScope.run(new DefaultTaskScope<>(), body);
  }
}
