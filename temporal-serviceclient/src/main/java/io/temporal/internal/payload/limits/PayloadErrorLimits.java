package io.temporal.internal.payload.limits;

import io.grpc.CallOptions;
import io.grpc.stub.AbstractStub;
import javax.annotation.Nullable;

/**
 * Holds the namespace's payload and memo error limits, in bytes, which are attached to a single
 * call through {@link #CALL_OPTIONS_KEY}. Only workers attach them, so calls made by clients are
 * checked against the warning thresholds only. A {@code 0} limit disables error enforcement for
 * that class.
 */
public final class PayloadErrorLimits {
  public static final CallOptions.Key<PayloadErrorLimits> CALL_OPTIONS_KEY =
      CallOptions.Key.create("temporal-payload-error-limits");

  private final long blob;
  private final long memo;

  public PayloadErrorLimits(long blob, long memo) {
    this.blob = blob;
    this.memo = memo;
  }

  /**
   * Attaches {@code limits} to calls made through {@code stub}. Returns {@code stub} unchanged when
   * there are no limits, because a call option cannot hold a null value.
   */
  public static <S extends AbstractStub<S>> S attach(S stub, @Nullable PayloadErrorLimits limits) {
    return limits == null ? stub : stub.withOption(CALL_OPTIONS_KEY, limits);
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
