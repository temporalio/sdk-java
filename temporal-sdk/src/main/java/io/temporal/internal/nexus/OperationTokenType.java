package io.temporal.internal.nexus;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum OperationTokenType {
  UNKNOWN(0),
  WORKFLOW_RUN(1),
  // Values 2 and 3 are reserved for future token types (e.g. update-workflow, get-workflow-result).
  ACTIVITY_EXECUTION(4);

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
