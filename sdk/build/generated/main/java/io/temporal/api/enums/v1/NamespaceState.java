// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/enums/v1/namespace.proto

package io.temporal.api.enums.v1;

/**
 * Protobuf enum {@code temporal.api.enums.v1.NamespaceState}
 */
public enum NamespaceState
    implements com.google.protobuf.ProtocolMessageEnum {
  /**
   * <code>NAMESPACE_STATE_UNSPECIFIED = 0;</code>
   */
  NAMESPACE_STATE_UNSPECIFIED(0),
  /**
   * <code>NAMESPACE_STATE_REGISTERED = 1;</code>
   */
  NAMESPACE_STATE_REGISTERED(1),
  /**
   * <code>NAMESPACE_STATE_DEPRECATED = 2;</code>
   */
  NAMESPACE_STATE_DEPRECATED(2),
  /**
   * <code>NAMESPACE_STATE_DELETED = 3;</code>
   */
  NAMESPACE_STATE_DELETED(3),
  UNRECOGNIZED(-1),
  ;

  /**
   * <code>NAMESPACE_STATE_UNSPECIFIED = 0;</code>
   */
  public static final int NAMESPACE_STATE_UNSPECIFIED_VALUE = 0;
  /**
   * <code>NAMESPACE_STATE_REGISTERED = 1;</code>
   */
  public static final int NAMESPACE_STATE_REGISTERED_VALUE = 1;
  /**
   * <code>NAMESPACE_STATE_DEPRECATED = 2;</code>
   */
  public static final int NAMESPACE_STATE_DEPRECATED_VALUE = 2;
  /**
   * <code>NAMESPACE_STATE_DELETED = 3;</code>
   */
  public static final int NAMESPACE_STATE_DELETED_VALUE = 3;


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
  public static NamespaceState valueOf(int value) {
    return forNumber(value);
  }

  /**
   * @param value The numeric wire value of the corresponding enum entry.
   * @return The enum associated with the given numeric wire value.
   */
  public static NamespaceState forNumber(int value) {
    switch (value) {
      case 0: return NAMESPACE_STATE_UNSPECIFIED;
      case 1: return NAMESPACE_STATE_REGISTERED;
      case 2: return NAMESPACE_STATE_DEPRECATED;
      case 3: return NAMESPACE_STATE_DELETED;
      default: return null;
    }
  }

  public static com.google.protobuf.Internal.EnumLiteMap<NamespaceState>
      internalGetValueMap() {
    return internalValueMap;
  }
  private static final com.google.protobuf.Internal.EnumLiteMap<
      NamespaceState> internalValueMap =
        new com.google.protobuf.Internal.EnumLiteMap<NamespaceState>() {
          public NamespaceState findValueByNumber(int number) {
            return NamespaceState.forNumber(number);
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
    return io.temporal.api.enums.v1.NamespaceProto.getDescriptor().getEnumTypes().get(0);
  }

  private static final NamespaceState[] VALUES = values();

  public static NamespaceState valueOf(
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

  private NamespaceState(int value) {
    this.value = value;
  }

  // @@protoc_insertion_point(enum_scope:temporal.api.enums.v1.NamespaceState)
}

