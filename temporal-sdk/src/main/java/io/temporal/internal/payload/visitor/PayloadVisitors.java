package io.temporal.internal.payload.visitor;

import com.google.protobuf.Message;
import javax.annotation.Nonnull;

/**
 * Visits every payload within a proto message. A message with no payloads is returned unchanged.
 *
 * <p>This is an SDK-internal utility; it is not part of the public API.
 *
 * <pre>{@code
 * RespondWorkflowTaskCompletedRequest result =
 *     PayloadVisitors.visit(
 *         request,
 *         PayloadVisitorOptions.<CommandInfo>newBuilder((ctx, payloads) -> encode(ctx, payloads))
 *             .setMessageVisitor((current, msg) -> msg instanceof Command.Builder
 *                 ? CommandInfo.of((Command.Builder) msg) : current)
 *             .setConcurrency(4)
 *             .build());
 * }</pre>
 */
final class PayloadVisitors {
  private PayloadVisitors() {}

  /** Visits the payloads in {@code builder} in place. */
  public static <C> void visit(
      @Nonnull Message.Builder builder, @Nonnull PayloadVisitorOptions<C> options) {
    Traversal traversal =
        new Traversal(
            options.getPayloadVisitor(),
            options.getMessageVisitor(),
            options.getInitialContext(),
            options.isSkipSearchAttributes(),
            options.isSkipHeaders(),
            options.getConcurrency(),
            options.getExecutor(),
            GeneratedPayloadVisitor.REGISTRY);
    traversal.dispatch(builder);
    traversal.execute();
  }

  /**
   * Visits the payloads in {@code message}, returning a copy with replacements applied; the input
   * is unchanged.
   */
  @SuppressWarnings("unchecked")
  public static <C, T extends Message> T visit(
      @Nonnull T message, @Nonnull PayloadVisitorOptions<C> options) {
    Message.Builder builder = message.toBuilder();
    visit(builder, options);
    return (T) builder.build();
  }
}
