// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/failure/v1/message.proto

package io.temporal.api.failure.v1;

/**
 * Protobuf type {@code temporal.api.failure.v1.ResetWorkflowFailureInfo}
 */
public final class ResetWorkflowFailureInfo extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:temporal.api.failure.v1.ResetWorkflowFailureInfo)
    ResetWorkflowFailureInfoOrBuilder {
private static final long serialVersionUID = 0L;
  // Use ResetWorkflowFailureInfo.newBuilder() to construct.
  private ResetWorkflowFailureInfo(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private ResetWorkflowFailureInfo() {
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new ResetWorkflowFailureInfo();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private ResetWorkflowFailureInfo(
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
            io.temporal.api.common.v1.Payloads.Builder subBuilder = null;
            if (lastHeartbeatDetails_ != null) {
              subBuilder = lastHeartbeatDetails_.toBuilder();
            }
            lastHeartbeatDetails_ = input.readMessage(io.temporal.api.common.v1.Payloads.parser(), extensionRegistry);
            if (subBuilder != null) {
              subBuilder.mergeFrom(lastHeartbeatDetails_);
              lastHeartbeatDetails_ = subBuilder.buildPartial();
            }

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
    return io.temporal.api.failure.v1.MessageProto.internal_static_temporal_api_failure_v1_ResetWorkflowFailureInfo_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.temporal.api.failure.v1.MessageProto.internal_static_temporal_api_failure_v1_ResetWorkflowFailureInfo_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.temporal.api.failure.v1.ResetWorkflowFailureInfo.class, io.temporal.api.failure.v1.ResetWorkflowFailureInfo.Builder.class);
  }

  public static final int LAST_HEARTBEAT_DETAILS_FIELD_NUMBER = 1;
  private io.temporal.api.common.v1.Payloads lastHeartbeatDetails_;
  /**
   * <code>.temporal.api.common.v1.Payloads last_heartbeat_details = 1;</code>
   * @return Whether the lastHeartbeatDetails field is set.
   */
  @java.lang.Override
  public boolean hasLastHeartbeatDetails() {
    return lastHeartbeatDetails_ != null;
  }
  /**
   * <code>.temporal.api.common.v1.Payloads last_heartbeat_details = 1;</code>
   * @return The lastHeartbeatDetails.
   */
  @java.lang.Override
  public io.temporal.api.common.v1.Payloads getLastHeartbeatDetails() {
    return lastHeartbeatDetails_ == null ? io.temporal.api.common.v1.Payloads.getDefaultInstance() : lastHeartbeatDetails_;
  }
  /**
   * <code>.temporal.api.common.v1.Payloads last_heartbeat_details = 1;</code>
   */
  @java.lang.Override
  public io.temporal.api.common.v1.PayloadsOrBuilder getLastHeartbeatDetailsOrBuilder() {
    return getLastHeartbeatDetails();
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
    if (lastHeartbeatDetails_ != null) {
      output.writeMessage(1, getLastHeartbeatDetails());
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (lastHeartbeatDetails_ != null) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(1, getLastHeartbeatDetails());
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
    if (!(obj instanceof io.temporal.api.failure.v1.ResetWorkflowFailureInfo)) {
      return super.equals(obj);
    }
    io.temporal.api.failure.v1.ResetWorkflowFailureInfo other = (io.temporal.api.failure.v1.ResetWorkflowFailureInfo) obj;

    if (hasLastHeartbeatDetails() != other.hasLastHeartbeatDetails()) return false;
    if (hasLastHeartbeatDetails()) {
      if (!getLastHeartbeatDetails()
          .equals(other.getLastHeartbeatDetails())) return false;
    }
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
    if (hasLastHeartbeatDetails()) {
      hash = (37 * hash) + LAST_HEARTBEAT_DETAILS_FIELD_NUMBER;
      hash = (53 * hash) + getLastHeartbeatDetails().hashCode();
    }
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.temporal.api.failure.v1.ResetWorkflowFailureInfo parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.failure.v1.ResetWorkflowFailureInfo parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.failure.v1.ResetWorkflowFailureInfo parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.failure.v1.ResetWorkflowFailureInfo parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.failure.v1.ResetWorkflowFailureInfo parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.failure.v1.ResetWorkflowFailureInfo parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.failure.v1.ResetWorkflowFailureInfo parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.temporal.api.failure.v1.ResetWorkflowFailureInfo parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.temporal.api.failure.v1.ResetWorkflowFailureInfo parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.temporal.api.failure.v1.ResetWorkflowFailureInfo parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.temporal.api.failure.v1.ResetWorkflowFailureInfo parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.temporal.api.failure.v1.ResetWorkflowFailureInfo parseFrom(
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
  public static Builder newBuilder(io.temporal.api.failure.v1.ResetWorkflowFailureInfo prototype) {
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
   * Protobuf type {@code temporal.api.failure.v1.ResetWorkflowFailureInfo}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:temporal.api.failure.v1.ResetWorkflowFailureInfo)
      io.temporal.api.failure.v1.ResetWorkflowFailureInfoOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.temporal.api.failure.v1.MessageProto.internal_static_temporal_api_failure_v1_ResetWorkflowFailureInfo_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.temporal.api.failure.v1.MessageProto.internal_static_temporal_api_failure_v1_ResetWorkflowFailureInfo_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.temporal.api.failure.v1.ResetWorkflowFailureInfo.class, io.temporal.api.failure.v1.ResetWorkflowFailureInfo.Builder.class);
    }

    // Construct using io.temporal.api.failure.v1.ResetWorkflowFailureInfo.newBuilder()
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
      if (lastHeartbeatDetailsBuilder_ == null) {
        lastHeartbeatDetails_ = null;
      } else {
        lastHeartbeatDetails_ = null;
        lastHeartbeatDetailsBuilder_ = null;
      }
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.temporal.api.failure.v1.MessageProto.internal_static_temporal_api_failure_v1_ResetWorkflowFailureInfo_descriptor;
    }

    @java.lang.Override
    public io.temporal.api.failure.v1.ResetWorkflowFailureInfo getDefaultInstanceForType() {
      return io.temporal.api.failure.v1.ResetWorkflowFailureInfo.getDefaultInstance();
    }

    @java.lang.Override
    public io.temporal.api.failure.v1.ResetWorkflowFailureInfo build() {
      io.temporal.api.failure.v1.ResetWorkflowFailureInfo result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.temporal.api.failure.v1.ResetWorkflowFailureInfo buildPartial() {
      io.temporal.api.failure.v1.ResetWorkflowFailureInfo result = new io.temporal.api.failure.v1.ResetWorkflowFailureInfo(this);
      if (lastHeartbeatDetailsBuilder_ == null) {
        result.lastHeartbeatDetails_ = lastHeartbeatDetails_;
      } else {
        result.lastHeartbeatDetails_ = lastHeartbeatDetailsBuilder_.build();
      }
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
      if (other instanceof io.temporal.api.failure.v1.ResetWorkflowFailureInfo) {
        return mergeFrom((io.temporal.api.failure.v1.ResetWorkflowFailureInfo)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.temporal.api.failure.v1.ResetWorkflowFailureInfo other) {
      if (other == io.temporal.api.failure.v1.ResetWorkflowFailureInfo.getDefaultInstance()) return this;
      if (other.hasLastHeartbeatDetails()) {
        mergeLastHeartbeatDetails(other.getLastHeartbeatDetails());
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
      io.temporal.api.failure.v1.ResetWorkflowFailureInfo parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.temporal.api.failure.v1.ResetWorkflowFailureInfo) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private io.temporal.api.common.v1.Payloads lastHeartbeatDetails_;
    private com.google.protobuf.SingleFieldBuilderV3<
        io.temporal.api.common.v1.Payloads, io.temporal.api.common.v1.Payloads.Builder, io.temporal.api.common.v1.PayloadsOrBuilder> lastHeartbeatDetailsBuilder_;
    /**
     * <code>.temporal.api.common.v1.Payloads last_heartbeat_details = 1;</code>
     * @return Whether the lastHeartbeatDetails field is set.
     */
    public boolean hasLastHeartbeatDetails() {
      return lastHeartbeatDetailsBuilder_ != null || lastHeartbeatDetails_ != null;
    }
    /**
     * <code>.temporal.api.common.v1.Payloads last_heartbeat_details = 1;</code>
     * @return The lastHeartbeatDetails.
     */
    public io.temporal.api.common.v1.Payloads getLastHeartbeatDetails() {
      if (lastHeartbeatDetailsBuilder_ == null) {
        return lastHeartbeatDetails_ == null ? io.temporal.api.common.v1.Payloads.getDefaultInstance() : lastHeartbeatDetails_;
      } else {
        return lastHeartbeatDetailsBuilder_.getMessage();
      }
    }
    /**
     * <code>.temporal.api.common.v1.Payloads last_heartbeat_details = 1;</code>
     */
    public Builder setLastHeartbeatDetails(io.temporal.api.common.v1.Payloads value) {
      if (lastHeartbeatDetailsBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        lastHeartbeatDetails_ = value;
        onChanged();
      } else {
        lastHeartbeatDetailsBuilder_.setMessage(value);
      }

      return this;
    }
    /**
     * <code>.temporal.api.common.v1.Payloads last_heartbeat_details = 1;</code>
     */
    public Builder setLastHeartbeatDetails(
        io.temporal.api.common.v1.Payloads.Builder builderForValue) {
      if (lastHeartbeatDetailsBuilder_ == null) {
        lastHeartbeatDetails_ = builderForValue.build();
        onChanged();
      } else {
        lastHeartbeatDetailsBuilder_.setMessage(builderForValue.build());
      }

      return this;
    }
    /**
     * <code>.temporal.api.common.v1.Payloads last_heartbeat_details = 1;</code>
     */
    public Builder mergeLastHeartbeatDetails(io.temporal.api.common.v1.Payloads value) {
      if (lastHeartbeatDetailsBuilder_ == null) {
        if (lastHeartbeatDetails_ != null) {
          lastHeartbeatDetails_ =
            io.temporal.api.common.v1.Payloads.newBuilder(lastHeartbeatDetails_).mergeFrom(value).buildPartial();
        } else {
          lastHeartbeatDetails_ = value;
        }
        onChanged();
      } else {
        lastHeartbeatDetailsBuilder_.mergeFrom(value);
      }

      return this;
    }
    /**
     * <code>.temporal.api.common.v1.Payloads last_heartbeat_details = 1;</code>
     */
    public Builder clearLastHeartbeatDetails() {
      if (lastHeartbeatDetailsBuilder_ == null) {
        lastHeartbeatDetails_ = null;
        onChanged();
      } else {
        lastHeartbeatDetails_ = null;
        lastHeartbeatDetailsBuilder_ = null;
      }

      return this;
    }
    /**
     * <code>.temporal.api.common.v1.Payloads last_heartbeat_details = 1;</code>
     */
    public io.temporal.api.common.v1.Payloads.Builder getLastHeartbeatDetailsBuilder() {
      
      onChanged();
      return getLastHeartbeatDetailsFieldBuilder().getBuilder();
    }
    /**
     * <code>.temporal.api.common.v1.Payloads last_heartbeat_details = 1;</code>
     */
    public io.temporal.api.common.v1.PayloadsOrBuilder getLastHeartbeatDetailsOrBuilder() {
      if (lastHeartbeatDetailsBuilder_ != null) {
        return lastHeartbeatDetailsBuilder_.getMessageOrBuilder();
      } else {
        return lastHeartbeatDetails_ == null ?
            io.temporal.api.common.v1.Payloads.getDefaultInstance() : lastHeartbeatDetails_;
      }
    }
    /**
     * <code>.temporal.api.common.v1.Payloads last_heartbeat_details = 1;</code>
     */
    private com.google.protobuf.SingleFieldBuilderV3<
        io.temporal.api.common.v1.Payloads, io.temporal.api.common.v1.Payloads.Builder, io.temporal.api.common.v1.PayloadsOrBuilder> 
        getLastHeartbeatDetailsFieldBuilder() {
      if (lastHeartbeatDetailsBuilder_ == null) {
        lastHeartbeatDetailsBuilder_ = new com.google.protobuf.SingleFieldBuilderV3<
            io.temporal.api.common.v1.Payloads, io.temporal.api.common.v1.Payloads.Builder, io.temporal.api.common.v1.PayloadsOrBuilder>(
                getLastHeartbeatDetails(),
                getParentForChildren(),
                isClean());
        lastHeartbeatDetails_ = null;
      }
      return lastHeartbeatDetailsBuilder_;
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


    // @@protoc_insertion_point(builder_scope:temporal.api.failure.v1.ResetWorkflowFailureInfo)
  }

  // @@protoc_insertion_point(class_scope:temporal.api.failure.v1.ResetWorkflowFailureInfo)
  private static final io.temporal.api.failure.v1.ResetWorkflowFailureInfo DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.temporal.api.failure.v1.ResetWorkflowFailureInfo();
  }

  public static io.temporal.api.failure.v1.ResetWorkflowFailureInfo getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<ResetWorkflowFailureInfo>
      PARSER = new com.google.protobuf.AbstractParser<ResetWorkflowFailureInfo>() {
    @java.lang.Override
    public ResetWorkflowFailureInfo parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new ResetWorkflowFailureInfo(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<ResetWorkflowFailureInfo> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<ResetWorkflowFailureInfo> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.temporal.api.failure.v1.ResetWorkflowFailureInfo getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

