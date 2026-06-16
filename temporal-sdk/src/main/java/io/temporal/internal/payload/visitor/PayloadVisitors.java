package io.temporal.internal.payload.visitor;

import com.google.protobuf.Message;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/**
 * Visits every payload within a proto message.
 */
final class PayloadVisitors {
  private PayloadVisitors() {}

  /** Visits the payloads in {@code builder} in place. */
  public static <C> CompletableFuture<Void> visit(
      @Nonnull Message.Builder builder, @Nonnull PayloadVisitorOptions<C> options) {
    Traversal traversal =
        new Traversal(
            options.getPayloadVisitor(),
            options.getMessageVisitor(),
            options.getInitialContext(),
            options.isSkipSearchAttributes(),
            options.isSkipHeaders(),
            options.getConcurrency(),
            GeneratedPayloadVisitor.REGISTRY);
    try {
      traversal.dispatch(builder);
    } catch (Throwable t) {
      // Surface a walk failure through the future, so all failures reach the caller the same way.
      CompletableFuture<Void> failed = new CompletableFuture<>();
      failed.completeExceptionally(t);
      return failed;
    }
    return traversal.execute();
  }

  /** Completes with a copy that has the replacements applied; {@code message} is unchanged. */
  @SuppressWarnings("unchecked")
  public static <C, T extends Message> CompletableFuture<T> visit(
      @Nonnull T message, @Nonnull PayloadVisitorOptions<C> options) {
    Message.Builder builder = message.toBuilder();
    return visit(builder, options).thenApply(v -> (T) builder.build());
  }
}
