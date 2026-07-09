package io.temporal.internal.payload.visitor;

import com.google.protobuf.Message;
import java.util.function.Supplier;

/**
 * How to traverse one message type, and how to create an empty builder for it (used to unpack
 * {@code google.protobuf.Any} values).
 */
final class MessageRegistryEntry {
  final GeneratedVisitor visitor;
  final Supplier<Message.Builder> newBuilder;

  MessageRegistryEntry(GeneratedVisitor visitor, Supplier<Message.Builder> newBuilder) {
    this.visitor = visitor;
    this.newBuilder = newBuilder;
  }
}
