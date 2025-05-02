package io.temporal.workflow;

import io.temporal.internal.sync.WorkflowThreadLocalInternal;
import java.util.Objects;
import java.util.function.Supplier;

/** {@link ThreadLocal} analog for workflow code. */
public final class WorkflowThreadLocal<T> {

  private final WorkflowThreadLocalInternal<T> impl;
  private final Supplier<? extends T> supplier;

  private WorkflowThreadLocal(Supplier<? extends T> supplier, boolean useCaching) {
    this.supplier = Objects.requireNonNull(supplier);
    this.impl = new WorkflowThreadLocalInternal<>(useCaching);
  }

  public WorkflowThreadLocal() {
    this.supplier = () -> null;
    this.impl = new WorkflowThreadLocalInternal<>(false);
  }

  /**
   * Create an instance that returns the value returned by the given {@code Supplier} when {@link
   * #set(S)} has not yet been called in the thread. Note that the value returned by the {@code
   * Supplier} is not stored in the {@code WorkflowThreadLocal} implicitly; repeatedly calling
   * {@link #get()} will always re-execute the {@code Supplier} until you call {@link #set(S)} for
   * the first time. This differs from the behavior of {@code ThreadLocal}. If you want the value
   * returned by the {@code Supplier} to be stored in the {@code WorkflowThreadLocal}, which matches
   * the behavior of {@code ThreadLocal}, use {@link #withCachedInitial(Supplier)} instead.
   *
   * @param supplier Callback that will be executed whenever {@link #get()} is called, until {@link
   *     #set(S)} is called for the first time.
   * @return A {@code WorkflowThreadLocal} instance.
   * @param <S> The type stored in the {@code WorkflowThreadLocal}.
   * @deprecated Because the non-caching behavior of this API is typically not desirable, it's
   *     recommend to use {@link #withCachedInitial(Supplier)} instead.
   */
  @Deprecated
  public static <S> WorkflowThreadLocal<S> withInitial(Supplier<? extends S> supplier) {
    return new WorkflowThreadLocal<>(supplier, false);
  }

  /**
   * Create an instance that returns the value returned by the given {@code Supplier} when {@link
   * #set(S)} has not yet been called in the Workflow, and then stores the returned value inside the
   * {@code WorkflowThreadLocal}.
   *
   * @param supplier Callback that will be executed when {@link #get()} is called for the first
   *     time, if {@link #set(S)} has not already been called.
   * @return A {@code WorkflowThreadLocal} instance.
   * @param <S> The type stored in the {@code WorkflowThreadLocal}.
   */
  public static <S> WorkflowThreadLocal<S> withCachedInitial(Supplier<? extends S> supplier) {
    return new WorkflowThreadLocal<>(supplier, true);
  }

  public T get() {
    return impl.get(supplier);
  }

  public void set(T value) {
    impl.set(value);
  }
}
