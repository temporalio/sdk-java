package io.temporal.internal.concurrent.structured;

import java.util.concurrent.CancellationException;
import java.util.function.Function;

/**
 * A settled outcome of a task: {@code SUCCESS}, {@code FAILURE}, or {@code CANCELLED}.
 *
 * <p>This is the element type returned by {@link TaskScope#awaitAllSettled}.
 *
 * @param <T> the success value type
 */
public final class Result<T> {

  public enum Status {
    SUCCESS,
    FAILURE,
    CANCELLED
  }

  private final Status status;
  private final T value;
  private final Throwable cause;

  private Result(Status status, T value, Throwable cause) {
    this.status = status;
    this.value = value;
    this.cause = cause;
  }

  public static <T> Result<T> success(T value) {
    return new Result<>(Status.SUCCESS, value, null);
  }

  public static <T> Result<T> failure(Throwable t) {
    return new Result<>(Status.FAILURE, null, t);
  }

  public static <T> Result<T> cancelled() {
    return new Result<>(Status.CANCELLED, null, new CancellationException());
  }

  public Status status() {
    return status;
  }

  public boolean isSuccess() {
    return status == Status.SUCCESS;
  }

  public boolean isFailure() {
    return status == Status.FAILURE;
  }

  public boolean isCancelled() {
    return status == Status.CANCELLED;
  }

  /**
   * @return the value on success; otherwise throws {@link IllegalStateException} (cause attached).
   */
  public T get() {
    if (status == Status.SUCCESS) return value;
    throw new IllegalStateException("not a success: " + status, cause);
  }

  /**
   * @return the value on success, else {@code other}.
   */
  public T orElse(T other) {
    return status == Status.SUCCESS ? value : other;
  }

  /**
   * @return the throwable for FAILURE/CANCELLED, or {@code null} for SUCCESS.
   */
  public Throwable cause() {
    return cause;
  }

  /** Maps the success value, leaving FAILURE/CANCELLED untouched. */
  @SuppressWarnings("unchecked")
  public <R> Result<R> map(Function<? super T, ? extends R> fn) {
    return status == Status.SUCCESS ? Result.success(fn.apply(value)) : (Result<R>) this;
  }

  /** Collapses both branches into one value. CANCELLED is routed to {@code onFailure}. */
  public <R> R fold(
      Function<? super T, ? extends R> onSuccess,
      Function<? super Throwable, ? extends R> onFailure) {
    return status == Status.SUCCESS ? onSuccess.apply(value) : onFailure.apply(cause);
  }

  @Override
  public String toString() {
    switch (status) {
      case SUCCESS:
        return "Success(" + value + ")";
      case CANCELLED:
        return "Cancelled";
      default:
        return "Failure(" + cause + ")";
    }
  }
}
