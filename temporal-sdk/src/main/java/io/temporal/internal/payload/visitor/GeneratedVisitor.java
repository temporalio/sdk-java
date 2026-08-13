package io.temporal.internal.payload.visitor;

import com.google.protobuf.Message;

/**
 * Generated traversal for one message type: visits the message's payload fields and recurses into
 * its child messages. There is one per message type that can contain a payload.
 */
interface GeneratedVisitor {
  void visit(Traversal traversal, Message.Builder builder);
}
