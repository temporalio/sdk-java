package io.temporal.internal.common;

import io.temporal.api.protocol.v1.Message;

public class ProtocolUtils {
  public static String getProtocol(Message message) {
    String typeUrl = message.getBody().getTypeUrl();
    int slashIndex = typeUrl.lastIndexOf("/");
    if (slashIndex == -1) {
      throw new IllegalArgumentException("Message body type URL invalid");
    }
    String typeName = typeUrl.substring(slashIndex + 1, typeUrl.length());
    String protocolName = typeName;
    int dotIndex = typeName.lastIndexOf(".");
    if (dotIndex != -1) {
      protocolName = protocolName.substring(0, dotIndex);
    }
    return protocolName;
  }
}
