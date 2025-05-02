package io.temporal.internal.nexus;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum OperationTokenType {
  UNKNOWN(0),
  WORKFLOW_RUN(1);

  private final int value;

  OperationTokenType(int i) {
    this.value = i;
  }

  @JsonValue
  public int toValue() {
    return value;
  }

  @JsonCreator
  public static OperationTokenType fromValue(Integer value) {
    if (value == null) {
      return UNKNOWN;
    }
    for (OperationTokenType b : OperationTokenType.values()) {
      if (b.value == value) {
        return b;
      }
    }
    return UNKNOWN;
  }
}
