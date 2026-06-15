package io.temporal.internal.payload.visitor;

import java.util.Objects;
import java.util.concurrent.Executor;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Options for visiting the payloads of a proto message.
 *
 * @param <C> type of the contextual value supplied to the visitor
 */
final class PayloadVisitorOptions<C> {
  private final @Nonnull PayloadVisitor<C> payloadVisitor;
  private final @Nullable MessageVisitor<C> messageVisitor;
  private final @Nullable C initialContext;
  private final boolean skipSearchAttributes;
  private final boolean skipHeaders;
  private final int concurrency;
  private final @Nullable Executor executor;

  private PayloadVisitorOptions(Builder<C> b) {
    this.payloadVisitor = b.payloadVisitor;
    this.messageVisitor = b.messageVisitor;
    this.initialContext = b.initialContext;
    this.skipSearchAttributes = b.skipSearchAttributes;
    this.skipHeaders = b.skipHeaders;
    this.concurrency = b.concurrency;
    this.executor = b.executor;
  }

  public static <C> Builder<C> newBuilder(@Nonnull PayloadVisitor<C> payloadVisitor) {
    return new Builder<>(payloadVisitor);
  }

  @Nonnull
  public PayloadVisitor<C> getPayloadVisitor() {
    return payloadVisitor;
  }

  @Nullable
  public MessageVisitor<C> getMessageVisitor() {
    return messageVisitor;
  }

  @Nullable
  public C getInitialContext() {
    return initialContext;
  }

  /** Whether search attribute payloads are skipped. */
  public boolean isSkipSearchAttributes() {
    return skipSearchAttributes;
  }

  /** Whether header payloads are skipped. */
  public boolean isSkipHeaders() {
    return skipHeaders;
  }

  /** Maximum number of visits that may run concurrently; {@code 1} is sequential. */
  public int getConcurrency() {
    return concurrency;
  }

  /** Executor for concurrent visits; {@code null} when concurrency is {@code 1}. */
  @Nullable
  public Executor getExecutor() {
    return executor;
  }

  public static final class Builder<C> {
    private final @Nonnull PayloadVisitor<C> payloadVisitor;
    private MessageVisitor<C> messageVisitor;
    private C initialContext;
    private boolean skipSearchAttributes;
    private boolean skipHeaders;
    private int concurrency = 1;
    private Executor executor;

    private Builder(@Nonnull PayloadVisitor<C> payloadVisitor) {
      this.payloadVisitor = Objects.requireNonNull(payloadVisitor, "payloadVisitor");
    }

    /** Optional. A callback invoked when entering each message. */
    public Builder<C> setMessageVisitor(@Nullable MessageVisitor<C> messageVisitor) {
      this.messageVisitor = messageVisitor;
      return this;
    }

    /** Optional. The contextual value in scope before any message is entered. */
    public Builder<C> setInitialContext(@Nullable C initialContext) {
      this.initialContext = initialContext;
      return this;
    }

    /** Whether to skip search attribute payloads. */
    public Builder<C> setSkipSearchAttributes(boolean skipSearchAttributes) {
      this.skipSearchAttributes = skipSearchAttributes;
      return this;
    }

    /** Whether to skip header payloads. */
    public Builder<C> setSkipHeaders(boolean skipHeaders) {
      this.skipHeaders = skipHeaders;
      return this;
    }

    /**
     * Maximum number of concurrent visits; must be at least {@code 1} (the default, sequential). A
     * value greater than {@code 1} requires an executor (see {@link #setExecutor}).
     */
    public Builder<C> setConcurrency(int concurrency) {
      this.concurrency = concurrency;
      return this;
    }

    /** Executor for concurrent visits. Required when concurrency is greater than {@code 1}. */
    public Builder<C> setExecutor(@Nullable Executor executor) {
      this.executor = executor;
      return this;
    }

    public PayloadVisitorOptions<C> build() {
      if (concurrency < 1) {
        throw new IllegalArgumentException("concurrency must be at least 1, got " + concurrency);
      }
      if (concurrency > 1 && executor == null) {
        throw new IllegalArgumentException(
            "executor is required when concurrency is greater than 1");
      }
      return new PayloadVisitorOptions<>(this);
    }
  }
}
