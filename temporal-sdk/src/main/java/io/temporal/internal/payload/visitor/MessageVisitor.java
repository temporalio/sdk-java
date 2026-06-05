package io.temporal.internal.payload.visitor;

import com.google.protobuf.MessageOrBuilder;

/**
 * Callback invoked when traversal enters a proto message. The returned value becomes the contextual
 * value in scope for that message and everything within it, and is restored to the enclosing value
 * once traversal leaves the message. The message is provided as a builder and may be inspected or
 * mutated.
 *
 * @param <C> type of the contextual value
 */
@FunctionalInterface
interface MessageVisitor<C> {
  /**
   * Handles a message being entered and returns the contextual value for it and its contents.
   *
   * @param current the contextual value in scope from the enclosing message
   * @param message the message being entered
   * @return the contextual value to use for this message and its contents
   */
  C onEnter(C current, MessageOrBuilder message);
}
