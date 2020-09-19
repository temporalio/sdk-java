// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/workflowservice/v1/request_response.proto

package io.temporal.api.workflowservice.v1;

/**
 * Protobuf type {@code temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest}
 */
public final class RespondWorkflowTaskFailedRequest extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest)
    RespondWorkflowTaskFailedRequestOrBuilder {
private static final long serialVersionUID = 0L;
  // Use RespondWorkflowTaskFailedRequest.newBuilder() to construct.
  private RespondWorkflowTaskFailedRequest(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private RespondWorkflowTaskFailedRequest() {
    taskToken_ = com.google.protobuf.ByteString.EMPTY;
    cause_ = 0;
    identity_ = "";
    binaryChecksum_ = "";
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new RespondWorkflowTaskFailedRequest();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private RespondWorkflowTaskFailedRequest(
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

            taskToken_ = input.readBytes();
            break;
          }
          case 16: {
            int rawValue = input.readEnum();

            cause_ = rawValue;
            break;
          }
          case 26: {
            io.temporal.api.failure.v1.Failure.Builder subBuilder = null;
            if (failure_ != null) {
              subBuilder = failure_.toBuilder();
            }
            failure_ = input.readMessage(io.temporal.api.failure.v1.Failure.parser(), extensionRegistry);
            if (subBuilder != null) {
              subBuilder.mergeFrom(failure_);
              failure_ = subBuilder.buildPartial();
            }

            break;
          }
          case 34: {
            java.lang.String s = input.readStringRequireUtf8();

            identity_ = s;
            break;
          }
          case 42: {
            java.lang.String s = input.readStringRequireUtf8();

            binaryChecksum_ = s;
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
    return io.temporal.api.workflowservice.v1.RequestResponseProto.internal_static_temporal_api_workflowservice_v1_RespondWorkflowTaskFailedRequest_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.temporal.api.workflowservice.v1.RequestResponseProto.internal_static_temporal_api_workflowservice_v1_RespondWorkflowTaskFailedRequest_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest.class, io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest.Builder.class);
  }

  public static final int TASK_TOKEN_FIELD_NUMBER = 1;
  private com.google.protobuf.ByteString taskToken_;
  /**
   * <code>bytes task_token = 1;</code>
   * @return The taskToken.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString getTaskToken() {
    return taskToken_;
  }

  public static final int CAUSE_FIELD_NUMBER = 2;
  private int cause_;
  /**
   * <code>.temporal.api.enums.v1.WorkflowTaskFailedCause cause = 2;</code>
   * @return The enum numeric value on the wire for cause.
   */
  @java.lang.Override public int getCauseValue() {
    return cause_;
  }
  /**
   * <code>.temporal.api.enums.v1.WorkflowTaskFailedCause cause = 2;</code>
   * @return The cause.
   */
  @java.lang.Override public io.temporal.api.enums.v1.WorkflowTaskFailedCause getCause() {
    @SuppressWarnings("deprecation")
    io.temporal.api.enums.v1.WorkflowTaskFailedCause result = io.temporal.api.enums.v1.WorkflowTaskFailedCause.valueOf(cause_);
    return result == null ? io.temporal.api.enums.v1.WorkflowTaskFailedCause.UNRECOGNIZED : result;
  }

  public static final int FAILURE_FIELD_NUMBER = 3;
  private io.temporal.api.failure.v1.Failure failure_;
  /**
   * <code>.temporal.api.failure.v1.Failure failure = 3;</code>
   * @return Whether the failure field is set.
   */
  @java.lang.Override
  public boolean hasFailure() {
    return failure_ != null;
  }
  /**
   * <code>.temporal.api.failure.v1.Failure failure = 3;</code>
   * @return The failure.
   */
  @java.lang.Override
  public io.temporal.api.failure.v1.Failure getFailure() {
    return failure_ == null ? io.temporal.api.failure.v1.Failure.getDefaultInstance() : failure_;
  }
  /**
   * <code>.temporal.api.failure.v1.Failure failure = 3;</code>
   */
  @java.lang.Override
  public io.temporal.api.failure.v1.FailureOrBuilder getFailureOrBuilder() {
    return getFailure();
  }

  public static final int IDENTITY_FIELD_NUMBER = 4;
  private volatile java.lang.Object identity_;
  /**
   * <code>string identity = 4;</code>
   * @return The identity.
   */
  @java.lang.Override
  public java.lang.String getIdentity() {
    java.lang.Object ref = identity_;
    if (ref instanceof java.lang.String) {
      return (java.lang.String) ref;
    } else {
      com.google.protobuf.ByteString bs = 
          (com.google.protobuf.ByteString) ref;
      java.lang.String s = bs.toStringUtf8();
      identity_ = s;
      return s;
    }
  }
  /**
   * <code>string identity = 4;</code>
   * @return The bytes for identity.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString
      getIdentityBytes() {
    java.lang.Object ref = identity_;
    if (ref instanceof java.lang.String) {
      com.google.protobuf.ByteString b = 
          com.google.protobuf.ByteString.copyFromUtf8(
              (java.lang.String) ref);
      identity_ = b;
      return b;
    } else {
      return (com.google.protobuf.ByteString) ref;
    }
  }

  public static final int BINARY_CHECKSUM_FIELD_NUMBER = 5;
  private volatile java.lang.Object binaryChecksum_;
  /**
   * <code>string binary_checksum = 5;</code>
   * @return The binaryChecksum.
   */
  @java.lang.Override
  public java.lang.String getBinaryChecksum() {
    java.lang.Object ref = binaryChecksum_;
    if (ref instanceof java.lang.String) {
      return (java.lang.String) ref;
    } else {
      com.google.protobuf.ByteString bs = 
          (com.google.protobuf.ByteString) ref;
      java.lang.String s = bs.toStringUtf8();
      binaryChecksum_ = s;
      return s;
    }
  }
  /**
   * <code>string binary_checksum = 5;</code>
   * @return The bytes for binaryChecksum.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString
      getBinaryChecksumBytes() {
    java.lang.Object ref = binaryChecksum_;
    if (ref instanceof java.lang.String) {
      com.google.protobuf.ByteString b = 
          com.google.protobuf.ByteString.copyFromUtf8(
              (java.lang.String) ref);
      binaryChecksum_ = b;
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
    if (!taskToken_.isEmpty()) {
      output.writeBytes(1, taskToken_);
    }
    if (cause_ != io.temporal.api.enums.v1.WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_UNSPECIFIED.getNumber()) {
      output.writeEnum(2, cause_);
    }
    if (failure_ != null) {
      output.writeMessage(3, getFailure());
    }
    if (!getIdentityBytes().isEmpty()) {
      com.google.protobuf.GeneratedMessageV3.writeString(output, 4, identity_);
    }
    if (!getBinaryChecksumBytes().isEmpty()) {
      com.google.protobuf.GeneratedMessageV3.writeString(output, 5, binaryChecksum_);
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (!taskToken_.isEmpty()) {
      size += com.google.protobuf.CodedOutputStream
        .computeBytesSize(1, taskToken_);
    }
    if (cause_ != io.temporal.api.enums.v1.WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_UNSPECIFIED.getNumber()) {
      size += com.google.protobuf.CodedOutputStream
        .computeEnumSize(2, cause_);
    }
    if (failure_ != null) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(3, getFailure());
    }
    if (!getIdentityBytes().isEmpty()) {
      size += com.google.protobuf.GeneratedMessageV3.computeStringSize(4, identity_);
    }
    if (!getBinaryChecksumBytes().isEmpty()) {
      size += com.google.protobuf.GeneratedMessageV3.computeStringSize(5, binaryChecksum_);
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
    if (!(obj instanceof io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest)) {
      return super.equals(obj);
    }
    io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest other = (io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest) obj;

    if (!getTaskToken()
        .equals(other.getTaskToken())) return false;
    if (cause_ != other.cause_) return false;
    if (hasFailure() != other.hasFailure()) return false;
    if (hasFailure()) {
      if (!getFailure()
          .equals(other.getFailure())) return false;
    }
    if (!getIdentity()
        .equals(other.getIdentity())) return false;
    if (!getBinaryChecksum()
        .equals(other.getBinaryChecksum())) return false;
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
    hash = (37 * hash) + TASK_TOKEN_FIELD_NUMBER;
    hash = (53 * hash) + getTaskToken().hashCode();
    hash = (37 * hash) + CAUSE_FIELD_NUMBER;
    hash = (53 * hash) + cause_;
    if (hasFailure()) {
      hash = (37 * hash) + FAILURE_FIELD_NUMBER;
      hash = (53 * hash) + getFailure().hashCode();
    }
    hash = (37 * hash) + IDENTITY_FIELD_NUMBER;
    hash = (53 * hash) + getIdentity().hashCode();
    hash = (37 * hash) + BINARY_CHECKSUM_FIELD_NUMBER;
    hash = (53 * hash) + getBinaryChecksum().hashCode();
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest parseFrom(
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
  public static Builder newBuilder(io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest prototype) {
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
   * Protobuf type {@code temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest)
      io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequestOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.temporal.api.workflowservice.v1.RequestResponseProto.internal_static_temporal_api_workflowservice_v1_RespondWorkflowTaskFailedRequest_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.temporal.api.workflowservice.v1.RequestResponseProto.internal_static_temporal_api_workflowservice_v1_RespondWorkflowTaskFailedRequest_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest.class, io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest.Builder.class);
    }

    // Construct using io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest.newBuilder()
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
      taskToken_ = com.google.protobuf.ByteString.EMPTY;

      cause_ = 0;

      if (failureBuilder_ == null) {
        failure_ = null;
      } else {
        failure_ = null;
        failureBuilder_ = null;
      }
      identity_ = "";

      binaryChecksum_ = "";

      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.temporal.api.workflowservice.v1.RequestResponseProto.internal_static_temporal_api_workflowservice_v1_RespondWorkflowTaskFailedRequest_descriptor;
    }

    @java.lang.Override
    public io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest getDefaultInstanceForType() {
      return io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest.getDefaultInstance();
    }

    @java.lang.Override
    public io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest build() {
      io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest buildPartial() {
      io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest result = new io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest(this);
      result.taskToken_ = taskToken_;
      result.cause_ = cause_;
      if (failureBuilder_ == null) {
        result.failure_ = failure_;
      } else {
        result.failure_ = failureBuilder_.build();
      }
      result.identity_ = identity_;
      result.binaryChecksum_ = binaryChecksum_;
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
      if (other instanceof io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest) {
        return mergeFrom((io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest other) {
      if (other == io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest.getDefaultInstance()) return this;
      if (other.getTaskToken() != com.google.protobuf.ByteString.EMPTY) {
        setTaskToken(other.getTaskToken());
      }
      if (other.cause_ != 0) {
        setCauseValue(other.getCauseValue());
      }
      if (other.hasFailure()) {
        mergeFailure(other.getFailure());
      }
      if (!other.getIdentity().isEmpty()) {
        identity_ = other.identity_;
        onChanged();
      }
      if (!other.getBinaryChecksum().isEmpty()) {
        binaryChecksum_ = other.binaryChecksum_;
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
      io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private com.google.protobuf.ByteString taskToken_ = com.google.protobuf.ByteString.EMPTY;
    /**
     * <code>bytes task_token = 1;</code>
     * @return The taskToken.
     */
    @java.lang.Override
    public com.google.protobuf.ByteString getTaskToken() {
      return taskToken_;
    }
    /**
     * <code>bytes task_token = 1;</code>
     * @param value The taskToken to set.
     * @return This builder for chaining.
     */
    public Builder setTaskToken(com.google.protobuf.ByteString value) {
      if (value == null) {
    throw new NullPointerException();
  }
  
      taskToken_ = value;
      onChanged();
      return this;
    }
    /**
     * <code>bytes task_token = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearTaskToken() {
      
      taskToken_ = getDefaultInstance().getTaskToken();
      onChanged();
      return this;
    }

    private int cause_ = 0;
    /**
     * <code>.temporal.api.enums.v1.WorkflowTaskFailedCause cause = 2;</code>
     * @return The enum numeric value on the wire for cause.
     */
    @java.lang.Override public int getCauseValue() {
      return cause_;
    }
    /**
     * <code>.temporal.api.enums.v1.WorkflowTaskFailedCause cause = 2;</code>
     * @param value The enum numeric value on the wire for cause to set.
     * @return This builder for chaining.
     */
    public Builder setCauseValue(int value) {
      
      cause_ = value;
      onChanged();
      return this;
    }
    /**
     * <code>.temporal.api.enums.v1.WorkflowTaskFailedCause cause = 2;</code>
     * @return The cause.
     */
    @java.lang.Override
    public io.temporal.api.enums.v1.WorkflowTaskFailedCause getCause() {
      @SuppressWarnings("deprecation")
      io.temporal.api.enums.v1.WorkflowTaskFailedCause result = io.temporal.api.enums.v1.WorkflowTaskFailedCause.valueOf(cause_);
      return result == null ? io.temporal.api.enums.v1.WorkflowTaskFailedCause.UNRECOGNIZED : result;
    }
    /**
     * <code>.temporal.api.enums.v1.WorkflowTaskFailedCause cause = 2;</code>
     * @param value The cause to set.
     * @return This builder for chaining.
     */
    public Builder setCause(io.temporal.api.enums.v1.WorkflowTaskFailedCause value) {
      if (value == null) {
        throw new NullPointerException();
      }
      
      cause_ = value.getNumber();
      onChanged();
      return this;
    }
    /**
     * <code>.temporal.api.enums.v1.WorkflowTaskFailedCause cause = 2;</code>
     * @return This builder for chaining.
     */
    public Builder clearCause() {
      
      cause_ = 0;
      onChanged();
      return this;
    }

    private io.temporal.api.failure.v1.Failure failure_;
    private com.google.protobuf.SingleFieldBuilderV3<
        io.temporal.api.failure.v1.Failure, io.temporal.api.failure.v1.Failure.Builder, io.temporal.api.failure.v1.FailureOrBuilder> failureBuilder_;
    /**
     * <code>.temporal.api.failure.v1.Failure failure = 3;</code>
     * @return Whether the failure field is set.
     */
    public boolean hasFailure() {
      return failureBuilder_ != null || failure_ != null;
    }
    /**
     * <code>.temporal.api.failure.v1.Failure failure = 3;</code>
     * @return The failure.
     */
    public io.temporal.api.failure.v1.Failure getFailure() {
      if (failureBuilder_ == null) {
        return failure_ == null ? io.temporal.api.failure.v1.Failure.getDefaultInstance() : failure_;
      } else {
        return failureBuilder_.getMessage();
      }
    }
    /**
     * <code>.temporal.api.failure.v1.Failure failure = 3;</code>
     */
    public Builder setFailure(io.temporal.api.failure.v1.Failure value) {
      if (failureBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        failure_ = value;
        onChanged();
      } else {
        failureBuilder_.setMessage(value);
      }

      return this;
    }
    /**
     * <code>.temporal.api.failure.v1.Failure failure = 3;</code>
     */
    public Builder setFailure(
        io.temporal.api.failure.v1.Failure.Builder builderForValue) {
      if (failureBuilder_ == null) {
        failure_ = builderForValue.build();
        onChanged();
      } else {
        failureBuilder_.setMessage(builderForValue.build());
      }

      return this;
    }
    /**
     * <code>.temporal.api.failure.v1.Failure failure = 3;</code>
     */
    public Builder mergeFailure(io.temporal.api.failure.v1.Failure value) {
      if (failureBuilder_ == null) {
        if (failure_ != null) {
          failure_ =
            io.temporal.api.failure.v1.Failure.newBuilder(failure_).mergeFrom(value).buildPartial();
        } else {
          failure_ = value;
        }
        onChanged();
      } else {
        failureBuilder_.mergeFrom(value);
      }

      return this;
    }
    /**
     * <code>.temporal.api.failure.v1.Failure failure = 3;</code>
     */
    public Builder clearFailure() {
      if (failureBuilder_ == null) {
        failure_ = null;
        onChanged();
      } else {
        failure_ = null;
        failureBuilder_ = null;
      }

      return this;
    }
    /**
     * <code>.temporal.api.failure.v1.Failure failure = 3;</code>
     */
    public io.temporal.api.failure.v1.Failure.Builder getFailureBuilder() {
      
      onChanged();
      return getFailureFieldBuilder().getBuilder();
    }
    /**
     * <code>.temporal.api.failure.v1.Failure failure = 3;</code>
     */
    public io.temporal.api.failure.v1.FailureOrBuilder getFailureOrBuilder() {
      if (failureBuilder_ != null) {
        return failureBuilder_.getMessageOrBuilder();
      } else {
        return failure_ == null ?
            io.temporal.api.failure.v1.Failure.getDefaultInstance() : failure_;
      }
    }
    /**
     * <code>.temporal.api.failure.v1.Failure failure = 3;</code>
     */
    private com.google.protobuf.SingleFieldBuilderV3<
        io.temporal.api.failure.v1.Failure, io.temporal.api.failure.v1.Failure.Builder, io.temporal.api.failure.v1.FailureOrBuilder> 
        getFailureFieldBuilder() {
      if (failureBuilder_ == null) {
        failureBuilder_ = new com.google.protobuf.SingleFieldBuilderV3<
            io.temporal.api.failure.v1.Failure, io.temporal.api.failure.v1.Failure.Builder, io.temporal.api.failure.v1.FailureOrBuilder>(
                getFailure(),
                getParentForChildren(),
                isClean());
        failure_ = null;
      }
      return failureBuilder_;
    }

    private java.lang.Object identity_ = "";
    /**
     * <code>string identity = 4;</code>
     * @return The identity.
     */
    public java.lang.String getIdentity() {
      java.lang.Object ref = identity_;
      if (!(ref instanceof java.lang.String)) {
        com.google.protobuf.ByteString bs =
            (com.google.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        identity_ = s;
        return s;
      } else {
        return (java.lang.String) ref;
      }
    }
    /**
     * <code>string identity = 4;</code>
     * @return The bytes for identity.
     */
    public com.google.protobuf.ByteString
        getIdentityBytes() {
      java.lang.Object ref = identity_;
      if (ref instanceof String) {
        com.google.protobuf.ByteString b = 
            com.google.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        identity_ = b;
        return b;
      } else {
        return (com.google.protobuf.ByteString) ref;
      }
    }
    /**
     * <code>string identity = 4;</code>
     * @param value The identity to set.
     * @return This builder for chaining.
     */
    public Builder setIdentity(
        java.lang.String value) {
      if (value == null) {
    throw new NullPointerException();
  }
  
      identity_ = value;
      onChanged();
      return this;
    }
    /**
     * <code>string identity = 4;</code>
     * @return This builder for chaining.
     */
    public Builder clearIdentity() {
      
      identity_ = getDefaultInstance().getIdentity();
      onChanged();
      return this;
    }
    /**
     * <code>string identity = 4;</code>
     * @param value The bytes for identity to set.
     * @return This builder for chaining.
     */
    public Builder setIdentityBytes(
        com.google.protobuf.ByteString value) {
      if (value == null) {
    throw new NullPointerException();
  }
  checkByteStringIsUtf8(value);
      
      identity_ = value;
      onChanged();
      return this;
    }

    private java.lang.Object binaryChecksum_ = "";
    /**
     * <code>string binary_checksum = 5;</code>
     * @return The binaryChecksum.
     */
    public java.lang.String getBinaryChecksum() {
      java.lang.Object ref = binaryChecksum_;
      if (!(ref instanceof java.lang.String)) {
        com.google.protobuf.ByteString bs =
            (com.google.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        binaryChecksum_ = s;
        return s;
      } else {
        return (java.lang.String) ref;
      }
    }
    /**
     * <code>string binary_checksum = 5;</code>
     * @return The bytes for binaryChecksum.
     */
    public com.google.protobuf.ByteString
        getBinaryChecksumBytes() {
      java.lang.Object ref = binaryChecksum_;
      if (ref instanceof String) {
        com.google.protobuf.ByteString b = 
            com.google.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        binaryChecksum_ = b;
        return b;
      } else {
        return (com.google.protobuf.ByteString) ref;
      }
    }
    /**
     * <code>string binary_checksum = 5;</code>
     * @param value The binaryChecksum to set.
     * @return This builder for chaining.
     */
    public Builder setBinaryChecksum(
        java.lang.String value) {
      if (value == null) {
    throw new NullPointerException();
  }
  
      binaryChecksum_ = value;
      onChanged();
      return this;
    }
    /**
     * <code>string binary_checksum = 5;</code>
     * @return This builder for chaining.
     */
    public Builder clearBinaryChecksum() {
      
      binaryChecksum_ = getDefaultInstance().getBinaryChecksum();
      onChanged();
      return this;
    }
    /**
     * <code>string binary_checksum = 5;</code>
     * @param value The bytes for binaryChecksum to set.
     * @return This builder for chaining.
     */
    public Builder setBinaryChecksumBytes(
        com.google.protobuf.ByteString value) {
      if (value == null) {
    throw new NullPointerException();
  }
  checkByteStringIsUtf8(value);
      
      binaryChecksum_ = value;
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


    // @@protoc_insertion_point(builder_scope:temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest)
  }

  // @@protoc_insertion_point(class_scope:temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest)
  private static final io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest();
  }

  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<RespondWorkflowTaskFailedRequest>
      PARSER = new com.google.protobuf.AbstractParser<RespondWorkflowTaskFailedRequest>() {
    @java.lang.Override
    public RespondWorkflowTaskFailedRequest parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new RespondWorkflowTaskFailedRequest(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<RespondWorkflowTaskFailedRequest> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<RespondWorkflowTaskFailedRequest> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

