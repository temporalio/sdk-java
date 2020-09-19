// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/enums/v1/failed_cause.proto

package io.temporal.api.enums.v1;

/**
 * Protobuf enum {@code temporal.api.enums.v1.CancelExternalWorkflowExecutionFailedCause}
 */
public enum CancelExternalWorkflowExecutionFailedCause
    implements com.google.protobuf.ProtocolMessageEnum {
  /**
   * <code>CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED_CAUSE_UNSPECIFIED = 0;</code>
   */
  CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED_CAUSE_UNSPECIFIED(0),
  /**
   * <code>CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED_CAUSE_EXTERNAL_WORKFLOW_EXECUTION_NOT_FOUND = 1;</code>
   */
  CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED_CAUSE_EXTERNAL_WORKFLOW_EXECUTION_NOT_FOUND(1),
  UNRECOGNIZED(-1),
  ;

  /**
   * <code>CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED_CAUSE_UNSPECIFIED = 0;</code>
   */
  public static final int CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED_CAUSE_UNSPECIFIED_VALUE = 0;
  /**
   * <code>CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED_CAUSE_EXTERNAL_WORKFLOW_EXECUTION_NOT_FOUND = 1;</code>
   */
  public static final int CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED_CAUSE_EXTERNAL_WORKFLOW_EXECUTION_NOT_FOUND_VALUE = 1;


  public final int getNumber() {
    if (this == UNRECOGNIZED) {
      throw new java.lang.IllegalArgumentException(
          "Can't get the number of an unknown enum value.");
    }
    return value;
  }

  /**
   * @param value The numeric wire value of the corresponding enum entry.
   * @return The enum associated with the given numeric wire value.
   * @deprecated Use {@link #forNumber(int)} instead.
   */
  @java.lang.Deprecated
  public static CancelExternalWorkflowExecutionFailedCause valueOf(int value) {
    return forNumber(value);
  }

  /**
   * @param value The numeric wire value of the corresponding enum entry.
   * @return The enum associated with the given numeric wire value.
   */
  public static CancelExternalWorkflowExecutionFailedCause forNumber(int value) {
    switch (value) {
      case 0: return CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED_CAUSE_UNSPECIFIED;
      case 1: return CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED_CAUSE_EXTERNAL_WORKFLOW_EXECUTION_NOT_FOUND;
      default: return null;
    }
  }

  public static com.google.protobuf.Internal.EnumLiteMap<CancelExternalWorkflowExecutionFailedCause>
      internalGetValueMap() {
    return internalValueMap;
  }
  private static final com.google.protobuf.Internal.EnumLiteMap<
      CancelExternalWorkflowExecutionFailedCause> internalValueMap =
        new com.google.protobuf.Internal.EnumLiteMap<CancelExternalWorkflowExecutionFailedCause>() {
          public CancelExternalWorkflowExecutionFailedCause findValueByNumber(int number) {
            return CancelExternalWorkflowExecutionFailedCause.forNumber(number);
          }
        };

  public final com.google.protobuf.Descriptors.EnumValueDescriptor
      getValueDescriptor() {
    if (this == UNRECOGNIZED) {
      throw new java.lang.IllegalStateException(
          "Can't get the descriptor of an unrecognized enum value.");
    }
    return getDescriptor().getValues().get(ordinal());
  }
  public final com.google.protobuf.Descriptors.EnumDescriptor
      getDescriptorForType() {
    return getDescriptor();
  }
  public static final com.google.protobuf.Descriptors.EnumDescriptor
      getDescriptor() {
    return io.temporal.api.enums.v1.FailedCauseProto.getDescriptor().getEnumTypes().get(2);
  }

  private static final CancelExternalWorkflowExecutionFailedCause[] VALUES = values();

  public static CancelExternalWorkflowExecutionFailedCause valueOf(
      com.google.protobuf.Descriptors.EnumValueDescriptor desc) {
    if (desc.getType() != getDescriptor()) {
      throw new java.lang.IllegalArgumentException(
        "EnumValueDescriptor is not for this type.");
    }
    if (desc.getIndex() == -1) {
      return UNRECOGNIZED;
    }
    return VALUES[desc.getIndex()];
  }

  private final int value;

  private CancelExternalWorkflowExecutionFailedCause(int value) {
    this.value = value;
  }

  // @@protoc_insertion_point(enum_scope:temporal.api.enums.v1.CancelExternalWorkflowExecutionFailedCause)
}

