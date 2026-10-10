package io.temporal.serviceclient;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import java.util.Objects;

/**
 * Configures warning thresholds for the size of outbound payload and memo fields. A field over its
 * threshold is logged with a {@code [TMPRL1103]} warning but still sent to the server.
 *
 * <p>Workers additionally enforce the namespace's error limits, failing a workflow or activity task
 * whose completion exceeds them instead of sending it. See {@code
 * WorkerOptions.Builder#setDisablePayloadErrorLimit}.
 *
 * <p>WARNING: Payload size-limit enforcement is experimental and the API may change in the future.
 */
public final class PayloadLimitsOptions {

  /** The default warning threshold for a payload-bearing field is 512 KiB. */
  public static final long DEFAULT_PAYLOADS_WARN_SIZE = 512 * 1024;

  /** The default warning threshold for a memo is 2 KiB. */
  public static final long DEFAULT_MEMO_WARN_SIZE = 2 * 1024;

  private static final PayloadLimitsOptions DEFAULT_INSTANCE = newBuilder().build();

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(PayloadLimitsOptions options) {
    return new Builder(options);
  }

  public static PayloadLimitsOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private final long payloadsWarnSize;
  private final long memoWarnSize;

  private PayloadLimitsOptions(long payloadsWarnSize, long memoWarnSize) {
    this.payloadsWarnSize = payloadsWarnSize;
    this.memoWarnSize = memoWarnSize;
  }

  /**
   * @return the warning threshold, in bytes, for the size of an outbound payload-bearing field; 0
   *     means the warning is disabled.
   */
  public long getPayloadsWarnSize() {
    return payloadsWarnSize;
  }

  /**
   * @return the warning threshold, in bytes, for the size of an outbound memo; 0 means the warning
   *     is disabled.
   */
  public long getMemoWarnSize() {
    return memoWarnSize;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PayloadLimitsOptions that = (PayloadLimitsOptions) o;
    return payloadsWarnSize == that.payloadsWarnSize && memoWarnSize == that.memoWarnSize;
  }

  @Override
  public int hashCode() {
    return Objects.hash(payloadsWarnSize, memoWarnSize);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("payloadsWarnSize", payloadsWarnSize)
        .add("memoWarnSize", memoWarnSize)
        .toString();
  }

  public static final class Builder {
    private long payloadsWarnSize = DEFAULT_PAYLOADS_WARN_SIZE;
    private long memoWarnSize = DEFAULT_MEMO_WARN_SIZE;

    private Builder() {}

    private Builder(PayloadLimitsOptions options) {
      if (options == null) {
        return;
      }
      this.payloadsWarnSize = options.payloadsWarnSize;
      this.memoWarnSize = options.memoWarnSize;
    }

    /**
     * Sets the warning threshold, in bytes, for the size of an outbound payload-bearing field.
     * Over-threshold fields are logged but still sent to the server. Defaults to 512 KiB; set to 0
     * to disable.
     */
    public Builder setPayloadsWarnSize(long payloadsWarnSize) {
      this.payloadsWarnSize = payloadsWarnSize;
      return this;
    }

    /**
     * Sets the warning threshold, in bytes, for the size of an outbound memo. Over-threshold memos
     * are logged but still sent to the server. Defaults to 2 KiB; set to 0 to disable.
     */
    public Builder setMemoWarnSize(long memoWarnSize) {
      this.memoWarnSize = memoWarnSize;
      return this;
    }

    public PayloadLimitsOptions build() {
      Preconditions.checkArgument(
          payloadsWarnSize >= 0, "payloadsWarnSize must not be negative: %s", payloadsWarnSize);
      Preconditions.checkArgument(
          memoWarnSize >= 0, "memoWarnSize must not be negative: %s", memoWarnSize);
      return new PayloadLimitsOptions(payloadsWarnSize, memoWarnSize);
    }
  }
}
