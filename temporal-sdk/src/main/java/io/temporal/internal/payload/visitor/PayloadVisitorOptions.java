package io.temporal.internal.payload.visitor;

import java.util.Objects;
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

  private PayloadVisitorOptions(Builder<C> b) {
    this.payloadVisitor = b.payloadVisitor;
    this.messageVisitor = b.messageVisitor;
    this.initialContext = b.initialContext;
    this.skipSearchAttributes = b.skipSearchAttributes;
    this.skipHeaders = b.skipHeaders;
    this.concurrency = b.concurrency;
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

  public boolean isSkipSearchAttributes() {
    return skipSearchAttributes;
  }

  public boolean isSkipHeaders() {
    return skipHeaders;
  }

  public int getConcurrency() {
    return concurrency;
  }

  public static final class Builder<C> {
    private final @Nonnull PayloadVisitor<C> payloadVisitor;
    private MessageVisitor<C> messageVisitor;
    private C initialContext;
    private boolean skipSearchAttributes;
    private boolean skipHeaders;
    private int concurrency = 1;

    private Builder(@Nonnull PayloadVisitor<C> payloadVisitor) {
      this.payloadVisitor = Objects.requireNonNull(payloadVisitor, "payloadVisitor");
    }

    public Builder<C> setMessageVisitor(@Nullable MessageVisitor<C> messageVisitor) {
      this.messageVisitor = messageVisitor;
      return this;
    }

    /** The contextual value in scope before any message is entered. */
    public Builder<C> setInitialContext(@Nullable C initialContext) {
      this.initialContext = initialContext;
      return this;
    }

    public Builder<C> setSkipSearchAttributes(boolean skipSearchAttributes) {
      this.skipSearchAttributes = skipSearchAttributes;
      return this;
    }

    public Builder<C> setSkipHeaders(boolean skipHeaders) {
      this.skipHeaders = skipHeaders;
      return this;
    }

    /** At least {@code 1} (sequential). Bounds outstanding visit futures; no executor needed. */
    public Builder<C> setConcurrency(int concurrency) {
      this.concurrency = concurrency;
      return this;
    }

    public PayloadVisitorOptions<C> build() {
      if (concurrency < 1) {
        throw new IllegalArgumentException("concurrency must be at least 1, got " + concurrency);
      }
      return new PayloadVisitorOptions<>(this);
    }
  }
}
