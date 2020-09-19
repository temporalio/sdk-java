// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/failure/v1/message.proto

package io.temporal.api.failure.v1;

/**
 * Protobuf type {@code temporal.api.failure.v1.ApplicationFailureInfo}
 */
public final class ApplicationFailureInfo extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:temporal.api.failure.v1.ApplicationFailureInfo)
    ApplicationFailureInfoOrBuilder {
private static final long serialVersionUID = 0L;
  // Use ApplicationFailureInfo.newBuilder() to construct.
  private ApplicationFailureInfo(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private ApplicationFailureInfo() {
    type_ = "";
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new ApplicationFailureInfo();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private ApplicationFailureInfo(
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

            type_ = s;
            break;
          }
          case 16: {

            nonRetryable_ = input.readBool();
            break;
          }
          case 26: {
            io.temporal.api.common.v1.Payloads.Builder subBuilder = null;
            if (details_ != null) {
              subBuilder = details_.toBuilder();
            }
            details_ = input.readMessage(io.temporal.api.common.v1.Payloads.parser(), extensionRegistry);
            if (subBuilder != null) {
              subBuilder.mergeFrom(details_);
              details_ = subBuilder.buildPartial();
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
    return io.temporal.api.failure.v1.MessageProto.internal_static_temporal_api_failure_v1_ApplicationFailureInfo_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.temporal.api.failure.v1.MessageProto.internal_static_temporal_api_failure_v1_ApplicationFailureInfo_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.temporal.api.failure.v1.ApplicationFailureInfo.class, io.temporal.api.failure.v1.ApplicationFailureInfo.Builder.class);
  }

  public static final int TYPE_FIELD_NUMBER = 1;
  private volatile java.lang.Object type_;
  /**
   * <code>string type = 1;</code>
   * @return The type.
   */
  @java.lang.Override
  public java.lang.String getType() {
    java.lang.Object ref = type_;
    if (ref instanceof java.lang.String) {
      return (java.lang.String) ref;
    } else {
      com.google.protobuf.ByteString bs = 
          (com.google.protobuf.ByteString) ref;
      java.lang.String s = bs.toStringUtf8();
      type_ = s;
      return s;
    }
  }
  /**
   * <code>string type = 1;</code>
   * @return The bytes for type.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString
      getTypeBytes() {
    java.lang.Object ref = type_;
    if (ref instanceof java.lang.String) {
      com.google.protobuf.ByteString b = 
          com.google.protobuf.ByteString.copyFromUtf8(
              (java.lang.String) ref);
      type_ = b;
      return b;
    } else {
      return (com.google.protobuf.ByteString) ref;
    }
  }

  public static final int NON_RETRYABLE_FIELD_NUMBER = 2;
  private boolean nonRetryable_;
  /**
   * <code>bool non_retryable = 2;</code>
   * @return The nonRetryable.
   */
  @java.lang.Override
  public boolean getNonRetryable() {
    return nonRetryable_;
  }

  public static final int DETAILS_FIELD_NUMBER = 3;
  private io.temporal.api.common.v1.Payloads details_;
  /**
   * <code>.temporal.api.common.v1.Payloads details = 3;</code>
   * @return Whether the details field is set.
   */
  @java.lang.Override
  public boolean hasDetails() {
    return details_ != null;
  }
  /**
   * <code>.temporal.api.common.v1.Payloads details = 3;</code>
   * @return The details.
   */
  @java.lang.Override
  public io.temporal.api.common.v1.Payloads getDetails() {
    return details_ == null ? io.temporal.api.common.v1.Payloads.getDefaultInstance() : details_;
  }
  /**
   * <code>.temporal.api.common.v1.Payloads details = 3;</code>
   */
  @java.lang.Override
  public io.temporal.api.common.v1.PayloadsOrBuilder getDetailsOrBuilder() {
    return getDetails();
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
    if (!getTypeBytes().isEmpty()) {
      com.google.protobuf.GeneratedMessageV3.writeString(output, 1, type_);
    }
    if (nonRetryable_ != false) {
      output.writeBool(2, nonRetryable_);
    }
    if (details_ != null) {
      output.writeMessage(3, getDetails());
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (!getTypeBytes().isEmpty()) {
      size += com.google.protobuf.GeneratedMessageV3.computeStringSize(1, type_);
    }
    if (nonRetryable_ != false) {
      size += com.google.protobuf.CodedOutputStream
        .computeBoolSize(2, nonRetryable_);
    }
    if (details_ != null) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(3, getDetails());
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
    if (!(obj instanceof io.temporal.api.failure.v1.ApplicationFailureInfo)) {
      return super.equals(obj);
    }
    io.temporal.api.failure.v1.ApplicationFailureInfo other = (io.temporal.api.failure.v1.ApplicationFailureInfo) obj;

    if (!getType()
        .equals(other.getType())) return false;
    if (getNonRetryable()
        != other.getNonRetryable()) return false;
    if (hasDetails() != other.hasDetails()) return false;
    if (hasDetails()) {
      if (!getDetails()
          .equals(other.getDetails())) return false;
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
    hash = (37 * hash) + TYPE_FIELD_NUMBER;
    hash = (53 * hash) + getType().hashCode();
    hash = (37 * hash) + NON_RETRYABLE_FIELD_NUMBER;
    hash = (53 * hash) + com.google.protobuf.Internal.hashBoolean(
        getNonRetryable());
    if (hasDetails()) {
      hash = (37 * hash) + DETAILS_FIELD_NUMBER;
      hash = (53 * hash) + getDetails().hashCode();
    }
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.temporal.api.failure.v1.ApplicationFailureInfo parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.failure.v1.ApplicationFailureInfo parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.failure.v1.ApplicationFailureInfo parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.failure.v1.ApplicationFailureInfo parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.failure.v1.ApplicationFailureInfo parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.failure.v1.ApplicationFailureInfo parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.failure.v1.ApplicationFailureInfo parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.temporal.api.failure.v1.ApplicationFailureInfo parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.temporal.api.failure.v1.ApplicationFailureInfo parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.temporal.api.failure.v1.ApplicationFailureInfo parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.temporal.api.failure.v1.ApplicationFailureInfo parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.temporal.api.failure.v1.ApplicationFailureInfo parseFrom(
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
  public static Builder newBuilder(io.temporal.api.failure.v1.ApplicationFailureInfo prototype) {
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
   * Protobuf type {@code temporal.api.failure.v1.ApplicationFailureInfo}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:temporal.api.failure.v1.ApplicationFailureInfo)
      io.temporal.api.failure.v1.ApplicationFailureInfoOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.temporal.api.failure.v1.MessageProto.internal_static_temporal_api_failure_v1_ApplicationFailureInfo_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.temporal.api.failure.v1.MessageProto.internal_static_temporal_api_failure_v1_ApplicationFailureInfo_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.temporal.api.failure.v1.ApplicationFailureInfo.class, io.temporal.api.failure.v1.ApplicationFailureInfo.Builder.class);
    }

    // Construct using io.temporal.api.failure.v1.ApplicationFailureInfo.newBuilder()
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
      type_ = "";

      nonRetryable_ = false;

      if (detailsBuilder_ == null) {
        details_ = null;
      } else {
        details_ = null;
        detailsBuilder_ = null;
      }
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.temporal.api.failure.v1.MessageProto.internal_static_temporal_api_failure_v1_ApplicationFailureInfo_descriptor;
    }

    @java.lang.Override
    public io.temporal.api.failure.v1.ApplicationFailureInfo getDefaultInstanceForType() {
      return io.temporal.api.failure.v1.ApplicationFailureInfo.getDefaultInstance();
    }

    @java.lang.Override
    public io.temporal.api.failure.v1.ApplicationFailureInfo build() {
      io.temporal.api.failure.v1.ApplicationFailureInfo result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.temporal.api.failure.v1.ApplicationFailureInfo buildPartial() {
      io.temporal.api.failure.v1.ApplicationFailureInfo result = new io.temporal.api.failure.v1.ApplicationFailureInfo(this);
      result.type_ = type_;
      result.nonRetryable_ = nonRetryable_;
      if (detailsBuilder_ == null) {
        result.details_ = details_;
      } else {
        result.details_ = detailsBuilder_.build();
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
      if (other instanceof io.temporal.api.failure.v1.ApplicationFailureInfo) {
        return mergeFrom((io.temporal.api.failure.v1.ApplicationFailureInfo)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.temporal.api.failure.v1.ApplicationFailureInfo other) {
      if (other == io.temporal.api.failure.v1.ApplicationFailureInfo.getDefaultInstance()) return this;
      if (!other.getType().isEmpty()) {
        type_ = other.type_;
        onChanged();
      }
      if (other.getNonRetryable() != false) {
        setNonRetryable(other.getNonRetryable());
      }
      if (other.hasDetails()) {
        mergeDetails(other.getDetails());
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
      io.temporal.api.failure.v1.ApplicationFailureInfo parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.temporal.api.failure.v1.ApplicationFailureInfo) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private java.lang.Object type_ = "";
    /**
     * <code>string type = 1;</code>
     * @return The type.
     */
    public java.lang.String getType() {
      java.lang.Object ref = type_;
      if (!(ref instanceof java.lang.String)) {
        com.google.protobuf.ByteString bs =
            (com.google.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        type_ = s;
        return s;
      } else {
        return (java.lang.String) ref;
      }
    }
    /**
     * <code>string type = 1;</code>
     * @return The bytes for type.
     */
    public com.google.protobuf.ByteString
        getTypeBytes() {
      java.lang.Object ref = type_;
      if (ref instanceof String) {
        com.google.protobuf.ByteString b = 
            com.google.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        type_ = b;
        return b;
      } else {
        return (com.google.protobuf.ByteString) ref;
      }
    }
    /**
     * <code>string type = 1;</code>
     * @param value The type to set.
     * @return This builder for chaining.
     */
    public Builder setType(
        java.lang.String value) {
      if (value == null) {
    throw new NullPointerException();
  }
  
      type_ = value;
      onChanged();
      return this;
    }
    /**
     * <code>string type = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearType() {
      
      type_ = getDefaultInstance().getType();
      onChanged();
      return this;
    }
    /**
     * <code>string type = 1;</code>
     * @param value The bytes for type to set.
     * @return This builder for chaining.
     */
    public Builder setTypeBytes(
        com.google.protobuf.ByteString value) {
      if (value == null) {
    throw new NullPointerException();
  }
  checkByteStringIsUtf8(value);
      
      type_ = value;
      onChanged();
      return this;
    }

    private boolean nonRetryable_ ;
    /**
     * <code>bool non_retryable = 2;</code>
     * @return The nonRetryable.
     */
    @java.lang.Override
    public boolean getNonRetryable() {
      return nonRetryable_;
    }
    /**
     * <code>bool non_retryable = 2;</code>
     * @param value The nonRetryable to set.
     * @return This builder for chaining.
     */
    public Builder setNonRetryable(boolean value) {
      
      nonRetryable_ = value;
      onChanged();
      return this;
    }
    /**
     * <code>bool non_retryable = 2;</code>
     * @return This builder for chaining.
     */
    public Builder clearNonRetryable() {
      
      nonRetryable_ = false;
      onChanged();
      return this;
    }

    private io.temporal.api.common.v1.Payloads details_;
    private com.google.protobuf.SingleFieldBuilderV3<
        io.temporal.api.common.v1.Payloads, io.temporal.api.common.v1.Payloads.Builder, io.temporal.api.common.v1.PayloadsOrBuilder> detailsBuilder_;
    /**
     * <code>.temporal.api.common.v1.Payloads details = 3;</code>
     * @return Whether the details field is set.
     */
    public boolean hasDetails() {
      return detailsBuilder_ != null || details_ != null;
    }
    /**
     * <code>.temporal.api.common.v1.Payloads details = 3;</code>
     * @return The details.
     */
    public io.temporal.api.common.v1.Payloads getDetails() {
      if (detailsBuilder_ == null) {
        return details_ == null ? io.temporal.api.common.v1.Payloads.getDefaultInstance() : details_;
      } else {
        return detailsBuilder_.getMessage();
      }
    }
    /**
     * <code>.temporal.api.common.v1.Payloads details = 3;</code>
     */
    public Builder setDetails(io.temporal.api.common.v1.Payloads value) {
      if (detailsBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        details_ = value;
        onChanged();
      } else {
        detailsBuilder_.setMessage(value);
      }

      return this;
    }
    /**
     * <code>.temporal.api.common.v1.Payloads details = 3;</code>
     */
    public Builder setDetails(
        io.temporal.api.common.v1.Payloads.Builder builderForValue) {
      if (detailsBuilder_ == null) {
        details_ = builderForValue.build();
        onChanged();
      } else {
        detailsBuilder_.setMessage(builderForValue.build());
      }

      return this;
    }
    /**
     * <code>.temporal.api.common.v1.Payloads details = 3;</code>
     */
    public Builder mergeDetails(io.temporal.api.common.v1.Payloads value) {
      if (detailsBuilder_ == null) {
        if (details_ != null) {
          details_ =
            io.temporal.api.common.v1.Payloads.newBuilder(details_).mergeFrom(value).buildPartial();
        } else {
          details_ = value;
        }
        onChanged();
      } else {
        detailsBuilder_.mergeFrom(value);
      }

      return this;
    }
    /**
     * <code>.temporal.api.common.v1.Payloads details = 3;</code>
     */
    public Builder clearDetails() {
      if (detailsBuilder_ == null) {
        details_ = null;
        onChanged();
      } else {
        details_ = null;
        detailsBuilder_ = null;
      }

      return this;
    }
    /**
     * <code>.temporal.api.common.v1.Payloads details = 3;</code>
     */
    public io.temporal.api.common.v1.Payloads.Builder getDetailsBuilder() {
      
      onChanged();
      return getDetailsFieldBuilder().getBuilder();
    }
    /**
     * <code>.temporal.api.common.v1.Payloads details = 3;</code>
     */
    public io.temporal.api.common.v1.PayloadsOrBuilder getDetailsOrBuilder() {
      if (detailsBuilder_ != null) {
        return detailsBuilder_.getMessageOrBuilder();
      } else {
        return details_ == null ?
            io.temporal.api.common.v1.Payloads.getDefaultInstance() : details_;
      }
    }
    /**
     * <code>.temporal.api.common.v1.Payloads details = 3;</code>
     */
    private com.google.protobuf.SingleFieldBuilderV3<
        io.temporal.api.common.v1.Payloads, io.temporal.api.common.v1.Payloads.Builder, io.temporal.api.common.v1.PayloadsOrBuilder> 
        getDetailsFieldBuilder() {
      if (detailsBuilder_ == null) {
        detailsBuilder_ = new com.google.protobuf.SingleFieldBuilderV3<
            io.temporal.api.common.v1.Payloads, io.temporal.api.common.v1.Payloads.Builder, io.temporal.api.common.v1.PayloadsOrBuilder>(
                getDetails(),
                getParentForChildren(),
                isClean());
        details_ = null;
      }
      return detailsBuilder_;
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


    // @@protoc_insertion_point(builder_scope:temporal.api.failure.v1.ApplicationFailureInfo)
  }

  // @@protoc_insertion_point(class_scope:temporal.api.failure.v1.ApplicationFailureInfo)
  private static final io.temporal.api.failure.v1.ApplicationFailureInfo DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.temporal.api.failure.v1.ApplicationFailureInfo();
  }

  public static io.temporal.api.failure.v1.ApplicationFailureInfo getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<ApplicationFailureInfo>
      PARSER = new com.google.protobuf.AbstractParser<ApplicationFailureInfo>() {
    @java.lang.Override
    public ApplicationFailureInfo parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new ApplicationFailureInfo(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<ApplicationFailureInfo> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<ApplicationFailureInfo> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.temporal.api.failure.v1.ApplicationFailureInfo getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

