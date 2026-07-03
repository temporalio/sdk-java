package io.temporal.internal.concurrent.structured;

import io.temporal.common.CancellationToken;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/** Reference implementation of {@link AsyncTask}. */
final class DefaultAsyncTask<T> implements AsyncTask<T> {

  final CompletableFuture<T> cf;
  final CancelSource source;
  private final CompletableFuture<Void> terminated;
  private final Runnable cancellationHook;
  private final Consumer<DefaultAsyncTask<?>> taskRegistrar;
  private final BiConsumer<DefaultAsyncTask<?>, DefaultAsyncTask<?>> resultRegistrar;
  private final List<DefaultAsyncTask<?>> children = new CopyOnWriteArrayList<>();

  DefaultAsyncTask(
      CompletableFuture<T> cf,
      CancelSource source,
      Runnable cancellationHook,
      Consumer<DefaultAsyncTask<?>> taskRegistrar,
      BiConsumer<DefaultAsyncTask<?>, DefaultAsyncTask<?>> resultRegistrar) {
    this.cf = cf;
    this.source = source;
    this.cancellationHook = cancellationHook;
    this.taskRegistrar = taskRegistrar;
    this.resultRegistrar = resultRegistrar;
    this.terminated = cf.handle((v, e) -> (Void) null);
    source
        .token()
        .onCancel(
            () -> {
              if (cancellationHook != null) {
                cancellationHook.run();
              }
              cf.completeExceptionally(new CancellationException());
            });
  }

  @Override
  public <R> AsyncTask<R> map(Function<? super T, ? extends R> fn) {
    CompletableFuture<R> next = new CompletableFuture<>();
    cf.whenComplete(
        (value, error) -> {
          if (error != null) {
            next.completeExceptionally(error);
            return;
          }
          if (source.token().isCancellationRequested()) {
            next.completeExceptionally(new CancellationException());
            return;
          }
          try {
            next.complete(fn.apply(value));
          } catch (Throwable t) {
            next.completeExceptionally(t);
          }
        });
    return derive(next);
  }

  @Override
  public AsyncTask<T> recover(Function<? super Throwable, ? extends T> fn) {
    CompletableFuture<T> next = new CompletableFuture<>();
    cf.whenComplete(
        (value, error) -> {
          if (error == null) {
            if (source.token().isCancellationRequested()) {
              next.completeExceptionally(new CancellationException());
            } else {
              next.complete(value);
            }
            return;
          }

          Throwable unwrapped = unwrap(error);
          if (unwrapped instanceof CancellationException) {
            next.completeExceptionally(unwrapped);
            return;
          }

          try {
            next.complete(fn.apply(unwrapped));
          } catch (Throwable t) {
            next.completeExceptionally(t);
          }
        });
    return derive(next);
  }

  private <R> DefaultAsyncTask<R> derive(CompletableFuture<R> next) {
    DefaultAsyncTask<R> child =
        new DefaultAsyncTask<>(
            next, CancelSource.linkedTo(source.token()), null, taskRegistrar, resultRegistrar);
    children.add(child);
    taskRegistrar.accept(child);
    resultRegistrar.accept(this, child);
    return child;
  }

  @Override
  public AsyncTask<T> whenSettled(BiConsumer<? super T, ? super Throwable> cb) {
    cf.whenComplete((v, ex) -> cb.accept(v, ex == null ? null : unwrap(ex)));
    return this;
  }

  @Override
  public boolean cancel() {
    boolean first = !cf.isDone();
    source.cancel();
    cf.completeExceptionally(new CancellationException());
    for (DefaultAsyncTask<?> c : children) c.cancel();
    return first;
  }

  @Override
  public boolean isDone() {
    return cf.isDone();
  }

  @Override
  public boolean isCancelled() {
    if (!cf.isDone()) {
      return source.isCancelled();
    }
    return joinSettled().isCancelled();
  }

  @Override
  public T join() {
    try {
      return cf.join();
    } catch (CompletionException e) {
      throw rethrow(unwrap(e));
    }
  }

  @Override
  public Result<T> joinSettled() {
    try {
      return Result.success(cf.join());
    } catch (CancellationException e) {
      return Result.cancelled();
    } catch (CompletionException e) {
      Throwable c = unwrap(e);
      return (c instanceof CancellationException) ? Result.cancelled() : Result.failure(c);
    }
  }

  @Override
  public CancellationToken token() {
    return source.token();
  }

  @Override
  public CompletableFuture<T> toCompletableFuture() {
    return cf;
  }

  /** Completes (normally, never exceptionally) once this task's future has settled. */
  CompletableFuture<Void> terminated() {
    return terminated;
  }

  static Throwable unwrap(Throwable t) {
    while ((t instanceof CompletionException || t instanceof ExecutionException)
        && t.getCause() != null) {
      t = t.getCause();
    }
    return t;
  }

  static RuntimeException rethrow(Throwable t) {
    if (t instanceof RuntimeException) return (RuntimeException) t;
    if (t instanceof Error) throw (Error) t;
    return new CompletionException(t);
  }
}
