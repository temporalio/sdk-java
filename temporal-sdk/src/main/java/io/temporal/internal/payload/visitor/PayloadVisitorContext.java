package io.temporal.internal.payload.visitor;

import com.google.protobuf.MessageOrBuilder;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The context for one payload visitor call: the contextual value in scope and the message that
 * contains the payloads being visited.
 *
 * @param <C> type of the contextual value
 */
final class PayloadVisitorContext<C> {
  private final @Nullable C context;
  private final @Nonnull MessageOrBuilder parent;

  PayloadVisitorContext(@Nullable C context, @Nonnull MessageOrBuilder parent) {
    this.context = context;
    this.parent = parent;
  }

  /** The contextual value in scope at this location, or {@code null} if none. */
  @Nullable
  public C getContext() {
    return context;
  }

  /** The message that directly contains the payloads being visited. */
  @Nonnull
  public MessageOrBuilder getParent() {
    return parent;
  }
}
