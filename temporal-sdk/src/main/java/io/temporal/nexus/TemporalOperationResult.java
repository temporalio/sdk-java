package io.temporal.nexus;

import com.google.common.base.Strings;
import io.temporal.common.Experimental;
import javax.annotation.Nullable;

/**
 * Unified result type for Temporal-backed Nexus operations. Encapsulates either a synchronous
 * result or an async operation token.
 *
 * <p>Use {@link #sync(Object)} for operations that complete immediately (e.g., signals). Use {@link
 * #async(String)} for operations that return an operation token for async completion (e.g., start
 * workflow).
 */
@Experimental
public final class TemporalOperationResult<R> {

  private final boolean isSync;
  @Nullable private final R syncResult;
  @Nullable private final String asyncOperationToken;

  private TemporalOperationResult(
      boolean isSync, @Nullable R syncResult, @Nullable String asyncOperationToken) {
    this.isSync = isSync;
    this.syncResult = syncResult;
    this.asyncOperationToken = asyncOperationToken;
  }

  /**
   * Creates a synchronous result.
   *
   * @param value the result value, may be null
   * @return a sync result wrapping the given value
   */
  public static <R> TemporalOperationResult<R> sync(@Nullable R value) {
    return new TemporalOperationResult<>(true, value, null);
  }

  /**
   * Creates an asynchronous result backed by an operation token.
   *
   * @param operationToken the operation token identifying the async operation
   * @return an async result wrapping the given token
   * @throws IllegalArgumentException if operationToken is null or empty
   */
  public static <R> TemporalOperationResult<R> async(String operationToken) {
    if (Strings.isNullOrEmpty(operationToken)) {
      throw new IllegalArgumentException("operationToken must not be null or empty");
    }
    return new TemporalOperationResult<>(false, null, operationToken);
  }

  /** Returns true if this is a synchronous result. */
  public boolean isSync() {
    return isSync;
  }

  /** Returns true if this is an asynchronous result backed by an operation token. */
  public boolean isAsync() {
    return !isSync;
  }

  /**
   * Returns the synchronous result value, or null if this is an async result.
   *
   * @return the sync result value, or null
   */
  @Nullable
  public R getSyncResult() {
    return syncResult;
  }

  /**
   * Returns the async operation token, or null if this is a sync result.
   *
   * @return the operation token, or null
   */
  @Nullable
  public String getAsyncOperationToken() {
    return asyncOperationToken;
  }
}
