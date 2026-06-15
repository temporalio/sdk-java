package io.temporal.internal.payload.visitor;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Options for visiting the messages of a proto message, without visiting individual payloads.
 *
 * @param <C> type of the contextual value supplied to the visitor
 */
final class MessageVisitorOptions<C> {
  private final @Nonnull MessageVisitor<C> messageVisitor;
  private final @Nullable C initialContext;

  private MessageVisitorOptions(Builder<C> b) {
    this.messageVisitor = b.messageVisitor;
    this.initialContext = b.initialContext;
  }

  public static <C> Builder<C> newBuilder(@Nonnull MessageVisitor<C> messageVisitor) {
    return new Builder<>(messageVisitor);
  }

  @Nonnull
  public MessageVisitor<C> getMessageVisitor() {
    return messageVisitor;
  }

  @Nullable
  public C getInitialContext() {
    return initialContext;
  }

  public static final class Builder<C> {
    private final @Nonnull MessageVisitor<C> messageVisitor;
    private C initialContext;

    private Builder(@Nonnull MessageVisitor<C> messageVisitor) {
      this.messageVisitor = Objects.requireNonNull(messageVisitor, "messageVisitor");
    }

    /** Optional. The contextual value in scope before any message is entered. */
    public Builder<C> setInitialContext(@Nullable C initialContext) {
      this.initialContext = initialContext;
      return this;
    }

    public MessageVisitorOptions<C> build() {
      return new MessageVisitorOptions<>(this);
    }
  }
}
