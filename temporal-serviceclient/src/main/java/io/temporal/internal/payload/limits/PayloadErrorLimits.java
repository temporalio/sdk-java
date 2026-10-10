package io.temporal.internal.payload.limits;

import io.grpc.CallOptions;

/**
 * Holds the namespace's payload and memo error limits, in bytes, which are attached to a single
 * call through {@link #CALL_OPTIONS_KEY}. Only workers attach them, so calls made by clients are
 * checked against the warning thresholds only. A {@code 0} limit disables error enforcement for
 * that class.
 */
public final class PayloadErrorLimits {
  /** Enforces no error limits. Calls without the option behave as if they carried this. */
  public static final PayloadErrorLimits NONE = new PayloadErrorLimits(0, 0);

  public static final CallOptions.Key<PayloadErrorLimits> CALL_OPTIONS_KEY =
      CallOptions.Key.createWithDefault("temporal-payload-error-limits", NONE);

  private final long blob;
  private final long memo;

  public PayloadErrorLimits(long blob, long memo) {
    this.blob = blob;
    this.memo = memo;
  }

  public long getBlob() {
    return blob;
  }

  public long getMemo() {
    return memo;
  }

  @Override
  public String toString() {
    return "PayloadErrorLimits{blob=" + blob + ", memo=" + memo + '}';
  }
}
