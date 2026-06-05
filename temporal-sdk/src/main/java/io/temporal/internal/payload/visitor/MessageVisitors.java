package io.temporal.internal.payload.visitor;

import com.google.protobuf.Message;
import javax.annotation.Nonnull;

/**
 * Visits the messages within a proto message, invoking the message visitor on each, without
 * visiting individual payloads. Only messages that can contain a payload are visited.
 *
 * <p>This is an SDK-internal utility; it is not part of the public API.
 */
final class MessageVisitors {
  private MessageVisitors() {}

  /** Visits the messages in {@code builder} in place. */
  public static <C> void visit(
      @Nonnull Message.Builder builder, @Nonnull MessageVisitorOptions<C> options) {
    Traversal traversal =
        new Traversal(
            null,
            options.getMessageVisitor(),
            options.getInitialContext(),
            /* skipSearchAttributes= */ false,
            /* skipHeaders= */ false,
            1,
            null,
            GeneratedPayloadVisitor.REGISTRY);
    traversal.dispatch(builder);
    traversal.execute();
  }

  /**
   * Visits the messages in {@code message}, returning a copy with any changes applied; the input is
   * unchanged.
   */
  @SuppressWarnings("unchecked")
  public static <C, T extends Message> T visit(
      @Nonnull T message, @Nonnull MessageVisitorOptions<C> options) {
    Message.Builder builder = message.toBuilder();
    visit(builder, options);
    return (T) builder.build();
  }
}
