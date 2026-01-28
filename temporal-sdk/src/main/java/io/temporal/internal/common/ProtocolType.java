package io.temporal.internal.common;

import java.util.Arrays;
import java.util.Optional;

public enum ProtocolType {
  UPDATE_V1("temporal.api.update.v1");

  private String type;

  ProtocolType(String type) {
    this.type = type;
  }

  public String getType() {
    return type;
  }

  public static Optional<ProtocolType> get(String type) {
    return Arrays.stream(ProtocolType.values())
        .filter(proto -> proto.type.equals(type))
        .findFirst();
  }
}
