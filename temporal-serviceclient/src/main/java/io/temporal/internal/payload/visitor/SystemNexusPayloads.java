package io.temporal.internal.payload.visitor;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.temporal.api.common.v1.Payload;

/** Fixed binary protobuf envelopes used by Temporal system Nexus operations. */
public final class SystemNexusPayloads {
  private static final String MARKER = "__temporal_system_payload";
  private static final ByteString TRUE = ByteString.copyFromUtf8("true");
  private static final ByteString ENCODING = ByteString.copyFromUtf8("binary/protobuf");

  private SystemNexusPayloads() {}

  public static boolean isSystemPayload(Payload payload) {
    return TRUE.equals(payload.getMetadataMap().get(MARKER));
  }

  public static Payload pack(Message message) {
    return Payload.newBuilder()
        .putMetadata(MARKER, TRUE)
        .putMetadata("encoding", ENCODING)
        .putMetadata(
            "messageType", ByteString.copyFromUtf8(message.getDescriptorForType().getFullName()))
        .setData(message.toByteString())
        .build();
  }

  public static Message.Builder unpack(Payload payload) {
    if (!isSystemPayload(payload) || !ENCODING.equals(payload.getMetadataMap().get("encoding"))) {
      throw new VisitorException("Invalid Temporal system payload encoding");
    }
    ByteString type = payload.getMetadataMap().get("messageType");
    String name = type == null ? "" : type.toStringUtf8();
    MessageRegistryEntry entry = GeneratedPayloadVisitor.SYSTEM_ROOTS.get(name);
    if (entry == null) {
      throw new VisitorException("Unknown Temporal system payload: " + name);
    }
    Message.Builder builder = entry.newBuilder.get();
    try {
      return builder.mergeFrom(payload.getData());
    } catch (InvalidProtocolBufferException e) {
      throw new VisitorException("Failed to unpack Temporal system payload: " + name, e);
    }
  }
}
