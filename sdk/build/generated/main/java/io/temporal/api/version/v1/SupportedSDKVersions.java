// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/version/v1/message.proto

package io.temporal.api.version.v1;

/**
 * <pre>
 * SupportedSDKVersions contains the support versions for SDK.
 * </pre>
 *
 * Protobuf type {@code temporal.api.version.v1.SupportedSDKVersions}
 */
public final class SupportedSDKVersions extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:temporal.api.version.v1.SupportedSDKVersions)
    SupportedSDKVersionsOrBuilder {
private static final long serialVersionUID = 0L;
  // Use SupportedSDKVersions.newBuilder() to construct.
  private SupportedSDKVersions(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private SupportedSDKVersions() {
    goSdk_ = "";
    javaSdk_ = "";
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new SupportedSDKVersions();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private SupportedSDKVersions(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    this();
    if (extensionRegistry == null) {
      throw new java.lang.NullPointerException();
    }
    com.google.protobuf.UnknownFieldSet.Builder unknownFields =
        com.google.protobuf.UnknownFieldSet.newBuilder();
    try {
      boolean done = false;
      while (!done) {
        int tag = input.readTag();
        switch (tag) {
          case 0:
            done = true;
            break;
          case 10: {
            java.lang.String s = input.readStringRequireUtf8();

            goSdk_ = s;
            break;
          }
          case 18: {
            java.lang.String s = input.readStringRequireUtf8();

            javaSdk_ = s;
            break;
          }
          default: {
            if (!parseUnknownField(
                input, unknownFields, extensionRegistry, tag)) {
              done = true;
            }
            break;
          }
        }
      }
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw e.setUnfinishedMessage(this);
    } catch (java.io.IOException e) {
      throw new com.google.protobuf.InvalidProtocolBufferException(
          e).setUnfinishedMessage(this);
    } finally {
      this.unknownFields = unknownFields.build();
      makeExtensionsImmutable();
    }
  }
  public static final com.google.protobuf.Descriptors.Descriptor
      getDescriptor() {
    return io.temporal.api.version.v1.MessageProto.internal_static_temporal_api_version_v1_SupportedSDKVersions_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.temporal.api.version.v1.MessageProto.internal_static_temporal_api_version_v1_SupportedSDKVersions_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.temporal.api.version.v1.SupportedSDKVersions.class, io.temporal.api.version.v1.SupportedSDKVersions.Builder.class);
  }

  public static final int GO_SDK_FIELD_NUMBER = 1;
  private volatile java.lang.Object goSdk_;
  /**
   * <code>string go_sdk = 1;</code>
   * @return The goSdk.
   */
  @java.lang.Override
  public java.lang.String getGoSdk() {
    java.lang.Object ref = goSdk_;
    if (ref instanceof java.lang.String) {
      return (java.lang.String) ref;
    } else {
      com.google.protobuf.ByteString bs = 
          (com.google.protobuf.ByteString) ref;
      java.lang.String s = bs.toStringUtf8();
      goSdk_ = s;
      return s;
    }
  }
  /**
   * <code>string go_sdk = 1;</code>
   * @return The bytes for goSdk.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString
      getGoSdkBytes() {
    java.lang.Object ref = goSdk_;
    if (ref instanceof java.lang.String) {
      com.google.protobuf.ByteString b = 
          com.google.protobuf.ByteString.copyFromUtf8(
              (java.lang.String) ref);
      goSdk_ = b;
      return b;
    } else {
      return (com.google.protobuf.ByteString) ref;
    }
  }

  public static final int JAVA_SDK_FIELD_NUMBER = 2;
  private volatile java.lang.Object javaSdk_;
  /**
   * <code>string java_sdk = 2;</code>
   * @return The javaSdk.
   */
  @java.lang.Override
  public java.lang.String getJavaSdk() {
    java.lang.Object ref = javaSdk_;
    if (ref instanceof java.lang.String) {
      return (java.lang.String) ref;
    } else {
      com.google.protobuf.ByteString bs = 
          (com.google.protobuf.ByteString) ref;
      java.lang.String s = bs.toStringUtf8();
      javaSdk_ = s;
      return s;
    }
  }
  /**
   * <code>string java_sdk = 2;</code>
   * @return The bytes for javaSdk.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString
      getJavaSdkBytes() {
    java.lang.Object ref = javaSdk_;
    if (ref instanceof java.lang.String) {
      com.google.protobuf.ByteString b = 
          com.google.protobuf.ByteString.copyFromUtf8(
              (java.lang.String) ref);
      javaSdk_ = b;
      return b;
    } else {
      return (com.google.protobuf.ByteString) ref;
    }
  }

  private byte memoizedIsInitialized = -1;
  @java.lang.Override
  public final boolean isInitialized() {
    byte isInitialized = memoizedIsInitialized;
    if (isInitialized == 1) return true;
    if (isInitialized == 0) return false;

    memoizedIsInitialized = 1;
    return true;
  }

  @java.lang.Override
  public void writeTo(com.google.protobuf.CodedOutputStream output)
                      throws java.io.IOException {
    if (!getGoSdkBytes().isEmpty()) {
      com.google.protobuf.GeneratedMessageV3.writeString(output, 1, goSdk_);
    }
    if (!getJavaSdkBytes().isEmpty()) {
      com.google.protobuf.GeneratedMessageV3.writeString(output, 2, javaSdk_);
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (!getGoSdkBytes().isEmpty()) {
      size += com.google.protobuf.GeneratedMessageV3.computeStringSize(1, goSdk_);
    }
    if (!getJavaSdkBytes().isEmpty()) {
      size += com.google.protobuf.GeneratedMessageV3.computeStringSize(2, javaSdk_);
    }
    size += unknownFields.getSerializedSize();
    memoizedSize = size;
    return size;
  }

  @java.lang.Override
  public boolean equals(final java.lang.Object obj) {
    if (obj == this) {
     return true;
    }
    if (!(obj instanceof io.temporal.api.version.v1.SupportedSDKVersions)) {
      return super.equals(obj);
    }
    io.temporal.api.version.v1.SupportedSDKVersions other = (io.temporal.api.version.v1.SupportedSDKVersions) obj;

    if (!getGoSdk()
        .equals(other.getGoSdk())) return false;
    if (!getJavaSdk()
        .equals(other.getJavaSdk())) return false;
    if (!unknownFields.equals(other.unknownFields)) return false;
    return true;
  }

  @java.lang.Override
  public int hashCode() {
    if (memoizedHashCode != 0) {
      return memoizedHashCode;
    }
    int hash = 41;
    hash = (19 * hash) + getDescriptor().hashCode();
    hash = (37 * hash) + GO_SDK_FIELD_NUMBER;
    hash = (53 * hash) + getGoSdk().hashCode();
    hash = (37 * hash) + JAVA_SDK_FIELD_NUMBER;
    hash = (53 * hash) + getJavaSdk().hashCode();
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.temporal.api.version.v1.SupportedSDKVersions parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.version.v1.SupportedSDKVersions parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.version.v1.SupportedSDKVersions parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.version.v1.SupportedSDKVersions parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.version.v1.SupportedSDKVersions parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.version.v1.SupportedSDKVersions parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.version.v1.SupportedSDKVersions parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.temporal.api.version.v1.SupportedSDKVersions parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.temporal.api.version.v1.SupportedSDKVersions parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.temporal.api.version.v1.SupportedSDKVersions parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.temporal.api.version.v1.SupportedSDKVersions parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.temporal.api.version.v1.SupportedSDKVersions parseFrom(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }

  @java.lang.Override
  public Builder newBuilderForType() { return newBuilder(); }
  public static Builder newBuilder() {
    return DEFAULT_INSTANCE.toBuilder();
  }
  public static Builder newBuilder(io.temporal.api.version.v1.SupportedSDKVersions prototype) {
    return DEFAULT_INSTANCE.toBuilder().mergeFrom(prototype);
  }
  @java.lang.Override
  public Builder toBuilder() {
    return this == DEFAULT_INSTANCE
        ? new Builder() : new Builder().mergeFrom(this);
  }

  @java.lang.Override
  protected Builder newBuilderForType(
      com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
    Builder builder = new Builder(parent);
    return builder;
  }
  /**
   * <pre>
   * SupportedSDKVersions contains the support versions for SDK.
   * </pre>
   *
   * Protobuf type {@code temporal.api.version.v1.SupportedSDKVersions}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:temporal.api.version.v1.SupportedSDKVersions)
      io.temporal.api.version.v1.SupportedSDKVersionsOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.temporal.api.version.v1.MessageProto.internal_static_temporal_api_version_v1_SupportedSDKVersions_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.temporal.api.version.v1.MessageProto.internal_static_temporal_api_version_v1_SupportedSDKVersions_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.temporal.api.version.v1.SupportedSDKVersions.class, io.temporal.api.version.v1.SupportedSDKVersions.Builder.class);
    }

    // Construct using io.temporal.api.version.v1.SupportedSDKVersions.newBuilder()
    private Builder() {
      maybeForceBuilderInitialization();
    }

    private Builder(
        com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
      super(parent);
      maybeForceBuilderInitialization();
    }
    private void maybeForceBuilderInitialization() {
      if (com.google.protobuf.GeneratedMessageV3
              .alwaysUseFieldBuilders) {
      }
    }
    @java.lang.Override
    public Builder clear() {
      super.clear();
      goSdk_ = "";

      javaSdk_ = "";

      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.temporal.api.version.v1.MessageProto.internal_static_temporal_api_version_v1_SupportedSDKVersions_descriptor;
    }

    @java.lang.Override
    public io.temporal.api.version.v1.SupportedSDKVersions getDefaultInstanceForType() {
      return io.temporal.api.version.v1.SupportedSDKVersions.getDefaultInstance();
    }

    @java.lang.Override
    public io.temporal.api.version.v1.SupportedSDKVersions build() {
      io.temporal.api.version.v1.SupportedSDKVersions result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.temporal.api.version.v1.SupportedSDKVersions buildPartial() {
      io.temporal.api.version.v1.SupportedSDKVersions result = new io.temporal.api.version.v1.SupportedSDKVersions(this);
      result.goSdk_ = goSdk_;
      result.javaSdk_ = javaSdk_;
      onBuilt();
      return result;
    }

    @java.lang.Override
    public Builder clone() {
      return super.clone();
    }
    @java.lang.Override
    public Builder setField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return super.setField(field, value);
    }
    @java.lang.Override
    public Builder clearField(
        com.google.protobuf.Descriptors.FieldDescriptor field) {
      return super.clearField(field);
    }
    @java.lang.Override
    public Builder clearOneof(
        com.google.protobuf.Descriptors.OneofDescriptor oneof) {
      return super.clearOneof(oneof);
    }
    @java.lang.Override
    public Builder setRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        int index, java.lang.Object value) {
      return super.setRepeatedField(field, index, value);
    }
    @java.lang.Override
    public Builder addRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return super.addRepeatedField(field, value);
    }
    @java.lang.Override
    public Builder mergeFrom(com.google.protobuf.Message other) {
      if (other instanceof io.temporal.api.version.v1.SupportedSDKVersions) {
        return mergeFrom((io.temporal.api.version.v1.SupportedSDKVersions)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.temporal.api.version.v1.SupportedSDKVersions other) {
      if (other == io.temporal.api.version.v1.SupportedSDKVersions.getDefaultInstance()) return this;
      if (!other.getGoSdk().isEmpty()) {
        goSdk_ = other.goSdk_;
        onChanged();
      }
      if (!other.getJavaSdk().isEmpty()) {
        javaSdk_ = other.javaSdk_;
        onChanged();
      }
      this.mergeUnknownFields(other.unknownFields);
      onChanged();
      return this;
    }

    @java.lang.Override
    public final boolean isInitialized() {
      return true;
    }

    @java.lang.Override
    public Builder mergeFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      io.temporal.api.version.v1.SupportedSDKVersions parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.temporal.api.version.v1.SupportedSDKVersions) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private java.lang.Object goSdk_ = "";
    /**
     * <code>string go_sdk = 1;</code>
     * @return The goSdk.
     */
    public java.lang.String getGoSdk() {
      java.lang.Object ref = goSdk_;
      if (!(ref instanceof java.lang.String)) {
        com.google.protobuf.ByteString bs =
            (com.google.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        goSdk_ = s;
        return s;
      } else {
        return (java.lang.String) ref;
      }
    }
    /**
     * <code>string go_sdk = 1;</code>
     * @return The bytes for goSdk.
     */
    public com.google.protobuf.ByteString
        getGoSdkBytes() {
      java.lang.Object ref = goSdk_;
      if (ref instanceof String) {
        com.google.protobuf.ByteString b = 
            com.google.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        goSdk_ = b;
        return b;
      } else {
        return (com.google.protobuf.ByteString) ref;
      }
    }
    /**
     * <code>string go_sdk = 1;</code>
     * @param value The goSdk to set.
     * @return This builder for chaining.
     */
    public Builder setGoSdk(
        java.lang.String value) {
      if (value == null) {
    throw new NullPointerException();
  }
  
      goSdk_ = value;
      onChanged();
      return this;
    }
    /**
     * <code>string go_sdk = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearGoSdk() {
      
      goSdk_ = getDefaultInstance().getGoSdk();
      onChanged();
      return this;
    }
    /**
     * <code>string go_sdk = 1;</code>
     * @param value The bytes for goSdk to set.
     * @return This builder for chaining.
     */
    public Builder setGoSdkBytes(
        com.google.protobuf.ByteString value) {
      if (value == null) {
    throw new NullPointerException();
  }
  checkByteStringIsUtf8(value);
      
      goSdk_ = value;
      onChanged();
      return this;
    }

    private java.lang.Object javaSdk_ = "";
    /**
     * <code>string java_sdk = 2;</code>
     * @return The javaSdk.
     */
    public java.lang.String getJavaSdk() {
      java.lang.Object ref = javaSdk_;
      if (!(ref instanceof java.lang.String)) {
        com.google.protobuf.ByteString bs =
            (com.google.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        javaSdk_ = s;
        return s;
      } else {
        return (java.lang.String) ref;
      }
    }
    /**
     * <code>string java_sdk = 2;</code>
     * @return The bytes for javaSdk.
     */
    public com.google.protobuf.ByteString
        getJavaSdkBytes() {
      java.lang.Object ref = javaSdk_;
      if (ref instanceof String) {
        com.google.protobuf.ByteString b = 
            com.google.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        javaSdk_ = b;
        return b;
      } else {
        return (com.google.protobuf.ByteString) ref;
      }
    }
    /**
     * <code>string java_sdk = 2;</code>
     * @param value The javaSdk to set.
     * @return This builder for chaining.
     */
    public Builder setJavaSdk(
        java.lang.String value) {
      if (value == null) {
    throw new NullPointerException();
  }
  
      javaSdk_ = value;
      onChanged();
      return this;
    }
    /**
     * <code>string java_sdk = 2;</code>
     * @return This builder for chaining.
     */
    public Builder clearJavaSdk() {
      
      javaSdk_ = getDefaultInstance().getJavaSdk();
      onChanged();
      return this;
    }
    /**
     * <code>string java_sdk = 2;</code>
     * @param value The bytes for javaSdk to set.
     * @return This builder for chaining.
     */
    public Builder setJavaSdkBytes(
        com.google.protobuf.ByteString value) {
      if (value == null) {
    throw new NullPointerException();
  }
  checkByteStringIsUtf8(value);
      
      javaSdk_ = value;
      onChanged();
      return this;
    }
    @java.lang.Override
    public final Builder setUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.setUnknownFields(unknownFields);
    }

    @java.lang.Override
    public final Builder mergeUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.mergeUnknownFields(unknownFields);
    }


    // @@protoc_insertion_point(builder_scope:temporal.api.version.v1.SupportedSDKVersions)
  }

  // @@protoc_insertion_point(class_scope:temporal.api.version.v1.SupportedSDKVersions)
  private static final io.temporal.api.version.v1.SupportedSDKVersions DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.temporal.api.version.v1.SupportedSDKVersions();
  }

  public static io.temporal.api.version.v1.SupportedSDKVersions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<SupportedSDKVersions>
      PARSER = new com.google.protobuf.AbstractParser<SupportedSDKVersions>() {
    @java.lang.Override
    public SupportedSDKVersions parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new SupportedSDKVersions(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<SupportedSDKVersions> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<SupportedSDKVersions> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.temporal.api.version.v1.SupportedSDKVersions getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

