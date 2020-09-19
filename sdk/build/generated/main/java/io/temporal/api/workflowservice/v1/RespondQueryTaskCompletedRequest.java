// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/workflowservice/v1/request_response.proto

package io.temporal.api.workflowservice.v1;

/**
 * <pre>
 * TODO:  deprecated APIs
 * </pre>
 *
 * Protobuf type {@code temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest}
 */
public final class RespondQueryTaskCompletedRequest extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest)
    RespondQueryTaskCompletedRequestOrBuilder {
private static final long serialVersionUID = 0L;
  // Use RespondQueryTaskCompletedRequest.newBuilder() to construct.
  private RespondQueryTaskCompletedRequest(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private RespondQueryTaskCompletedRequest() {
    taskToken_ = com.google.protobuf.ByteString.EMPTY;
    completedType_ = 0;
    errorMessage_ = "";
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new RespondQueryTaskCompletedRequest();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private RespondQueryTaskCompletedRequest(
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

            completedType_ = rawValue;
            break;
          }
          case 26: {
            io.temporal.api.common.v1.Payloads.Builder subBuilder = null;
            if (queryResult_ != null) {
              subBuilder = queryResult_.toBuilder();
            }
            queryResult_ = input.readMessage(io.temporal.api.common.v1.Payloads.parser(), extensionRegistry);
            if (subBuilder != null) {
              subBuilder.mergeFrom(queryResult_);
              queryResult_ = subBuilder.buildPartial();
            }

            break;
          }
          case 34: {
            java.lang.String s = input.readStringRequireUtf8();

            errorMessage_ = s;
            break;
          }
          case 42: {
            io.temporal.api.version.v1.WorkerVersionInfo.Builder subBuilder = null;
            if (workerVersionInfo_ != null) {
              subBuilder = workerVersionInfo_.toBuilder();
            }
            workerVersionInfo_ = input.readMessage(io.temporal.api.version.v1.WorkerVersionInfo.parser(), extensionRegistry);
            if (subBuilder != null) {
              subBuilder.mergeFrom(workerVersionInfo_);
              workerVersionInfo_ = subBuilder.buildPartial();
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
    return io.temporal.api.workflowservice.v1.RequestResponseProto.internal_static_temporal_api_workflowservice_v1_RespondQueryTaskCompletedRequest_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.temporal.api.workflowservice.v1.RequestResponseProto.internal_static_temporal_api_workflowservice_v1_RespondQueryTaskCompletedRequest_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest.class, io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest.Builder.class);
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

  public static final int COMPLETED_TYPE_FIELD_NUMBER = 2;
  private int completedType_;
  /**
   * <code>.temporal.api.enums.v1.QueryResultType completed_type = 2;</code>
   * @return The enum numeric value on the wire for completedType.
   */
  @java.lang.Override public int getCompletedTypeValue() {
    return completedType_;
  }
  /**
   * <code>.temporal.api.enums.v1.QueryResultType completed_type = 2;</code>
   * @return The completedType.
   */
  @java.lang.Override public io.temporal.api.enums.v1.QueryResultType getCompletedType() {
    @SuppressWarnings("deprecation")
    io.temporal.api.enums.v1.QueryResultType result = io.temporal.api.enums.v1.QueryResultType.valueOf(completedType_);
    return result == null ? io.temporal.api.enums.v1.QueryResultType.UNRECOGNIZED : result;
  }

  public static final int QUERY_RESULT_FIELD_NUMBER = 3;
  private io.temporal.api.common.v1.Payloads queryResult_;
  /**
   * <code>.temporal.api.common.v1.Payloads query_result = 3;</code>
   * @return Whether the queryResult field is set.
   */
  @java.lang.Override
  public boolean hasQueryResult() {
    return queryResult_ != null;
  }
  /**
   * <code>.temporal.api.common.v1.Payloads query_result = 3;</code>
   * @return The queryResult.
   */
  @java.lang.Override
  public io.temporal.api.common.v1.Payloads getQueryResult() {
    return queryResult_ == null ? io.temporal.api.common.v1.Payloads.getDefaultInstance() : queryResult_;
  }
  /**
   * <code>.temporal.api.common.v1.Payloads query_result = 3;</code>
   */
  @java.lang.Override
  public io.temporal.api.common.v1.PayloadsOrBuilder getQueryResultOrBuilder() {
    return getQueryResult();
  }

  public static final int ERROR_MESSAGE_FIELD_NUMBER = 4;
  private volatile java.lang.Object errorMessage_;
  /**
   * <code>string error_message = 4;</code>
   * @return The errorMessage.
   */
  @java.lang.Override
  public java.lang.String getErrorMessage() {
    java.lang.Object ref = errorMessage_;
    if (ref instanceof java.lang.String) {
      return (java.lang.String) ref;
    } else {
      com.google.protobuf.ByteString bs = 
          (com.google.protobuf.ByteString) ref;
      java.lang.String s = bs.toStringUtf8();
      errorMessage_ = s;
      return s;
    }
  }
  /**
   * <code>string error_message = 4;</code>
   * @return The bytes for errorMessage.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString
      getErrorMessageBytes() {
    java.lang.Object ref = errorMessage_;
    if (ref instanceof java.lang.String) {
      com.google.protobuf.ByteString b = 
          com.google.protobuf.ByteString.copyFromUtf8(
              (java.lang.String) ref);
      errorMessage_ = b;
      return b;
    } else {
      return (com.google.protobuf.ByteString) ref;
    }
  }

  public static final int WORKER_VERSION_INFO_FIELD_NUMBER = 5;
  private io.temporal.api.version.v1.WorkerVersionInfo workerVersionInfo_;
  /**
   * <code>.temporal.api.version.v1.WorkerVersionInfo worker_version_info = 5;</code>
   * @return Whether the workerVersionInfo field is set.
   */
  @java.lang.Override
  public boolean hasWorkerVersionInfo() {
    return workerVersionInfo_ != null;
  }
  /**
   * <code>.temporal.api.version.v1.WorkerVersionInfo worker_version_info = 5;</code>
   * @return The workerVersionInfo.
   */
  @java.lang.Override
  public io.temporal.api.version.v1.WorkerVersionInfo getWorkerVersionInfo() {
    return workerVersionInfo_ == null ? io.temporal.api.version.v1.WorkerVersionInfo.getDefaultInstance() : workerVersionInfo_;
  }
  /**
   * <code>.temporal.api.version.v1.WorkerVersionInfo worker_version_info = 5;</code>
   */
  @java.lang.Override
  public io.temporal.api.version.v1.WorkerVersionInfoOrBuilder getWorkerVersionInfoOrBuilder() {
    return getWorkerVersionInfo();
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
    if (completedType_ != io.temporal.api.enums.v1.QueryResultType.QUERY_RESULT_TYPE_UNSPECIFIED.getNumber()) {
      output.writeEnum(2, completedType_);
    }
    if (queryResult_ != null) {
      output.writeMessage(3, getQueryResult());
    }
    if (!getErrorMessageBytes().isEmpty()) {
      com.google.protobuf.GeneratedMessageV3.writeString(output, 4, errorMessage_);
    }
    if (workerVersionInfo_ != null) {
      output.writeMessage(5, getWorkerVersionInfo());
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
    if (completedType_ != io.temporal.api.enums.v1.QueryResultType.QUERY_RESULT_TYPE_UNSPECIFIED.getNumber()) {
      size += com.google.protobuf.CodedOutputStream
        .computeEnumSize(2, completedType_);
    }
    if (queryResult_ != null) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(3, getQueryResult());
    }
    if (!getErrorMessageBytes().isEmpty()) {
      size += com.google.protobuf.GeneratedMessageV3.computeStringSize(4, errorMessage_);
    }
    if (workerVersionInfo_ != null) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(5, getWorkerVersionInfo());
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
    if (!(obj instanceof io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest)) {
      return super.equals(obj);
    }
    io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest other = (io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest) obj;

    if (!getTaskToken()
        .equals(other.getTaskToken())) return false;
    if (completedType_ != other.completedType_) return false;
    if (hasQueryResult() != other.hasQueryResult()) return false;
    if (hasQueryResult()) {
      if (!getQueryResult()
          .equals(other.getQueryResult())) return false;
    }
    if (!getErrorMessage()
        .equals(other.getErrorMessage())) return false;
    if (hasWorkerVersionInfo() != other.hasWorkerVersionInfo()) return false;
    if (hasWorkerVersionInfo()) {
      if (!getWorkerVersionInfo()
          .equals(other.getWorkerVersionInfo())) return false;
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
    hash = (37 * hash) + TASK_TOKEN_FIELD_NUMBER;
    hash = (53 * hash) + getTaskToken().hashCode();
    hash = (37 * hash) + COMPLETED_TYPE_FIELD_NUMBER;
    hash = (53 * hash) + completedType_;
    if (hasQueryResult()) {
      hash = (37 * hash) + QUERY_RESULT_FIELD_NUMBER;
      hash = (53 * hash) + getQueryResult().hashCode();
    }
    hash = (37 * hash) + ERROR_MESSAGE_FIELD_NUMBER;
    hash = (53 * hash) + getErrorMessage().hashCode();
    if (hasWorkerVersionInfo()) {
      hash = (37 * hash) + WORKER_VERSION_INFO_FIELD_NUMBER;
      hash = (53 * hash) + getWorkerVersionInfo().hashCode();
    }
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest parseFrom(
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
  public static Builder newBuilder(io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest prototype) {
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
   * TODO:  deprecated APIs
   * </pre>
   *
   * Protobuf type {@code temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest)
      io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequestOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.temporal.api.workflowservice.v1.RequestResponseProto.internal_static_temporal_api_workflowservice_v1_RespondQueryTaskCompletedRequest_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.temporal.api.workflowservice.v1.RequestResponseProto.internal_static_temporal_api_workflowservice_v1_RespondQueryTaskCompletedRequest_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest.class, io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest.Builder.class);
    }

    // Construct using io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest.newBuilder()
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

      completedType_ = 0;

      if (queryResultBuilder_ == null) {
        queryResult_ = null;
      } else {
        queryResult_ = null;
        queryResultBuilder_ = null;
      }
      errorMessage_ = "";

      if (workerVersionInfoBuilder_ == null) {
        workerVersionInfo_ = null;
      } else {
        workerVersionInfo_ = null;
        workerVersionInfoBuilder_ = null;
      }
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.temporal.api.workflowservice.v1.RequestResponseProto.internal_static_temporal_api_workflowservice_v1_RespondQueryTaskCompletedRequest_descriptor;
    }

    @java.lang.Override
    public io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest getDefaultInstanceForType() {
      return io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest.getDefaultInstance();
    }

    @java.lang.Override
    public io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest build() {
      io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest buildPartial() {
      io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest result = new io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest(this);
      result.taskToken_ = taskToken_;
      result.completedType_ = completedType_;
      if (queryResultBuilder_ == null) {
        result.queryResult_ = queryResult_;
      } else {
        result.queryResult_ = queryResultBuilder_.build();
      }
      result.errorMessage_ = errorMessage_;
      if (workerVersionInfoBuilder_ == null) {
        result.workerVersionInfo_ = workerVersionInfo_;
      } else {
        result.workerVersionInfo_ = workerVersionInfoBuilder_.build();
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
      if (other instanceof io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest) {
        return mergeFrom((io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest other) {
      if (other == io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest.getDefaultInstance()) return this;
      if (other.getTaskToken() != com.google.protobuf.ByteString.EMPTY) {
        setTaskToken(other.getTaskToken());
      }
      if (other.completedType_ != 0) {
        setCompletedTypeValue(other.getCompletedTypeValue());
      }
      if (other.hasQueryResult()) {
        mergeQueryResult(other.getQueryResult());
      }
      if (!other.getErrorMessage().isEmpty()) {
        errorMessage_ = other.errorMessage_;
        onChanged();
      }
      if (other.hasWorkerVersionInfo()) {
        mergeWorkerVersionInfo(other.getWorkerVersionInfo());
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
      io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest) e.getUnfinishedMessage();
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

    private int completedType_ = 0;
    /**
     * <code>.temporal.api.enums.v1.QueryResultType completed_type = 2;</code>
     * @return The enum numeric value on the wire for completedType.
     */
    @java.lang.Override public int getCompletedTypeValue() {
      return completedType_;
    }
    /**
     * <code>.temporal.api.enums.v1.QueryResultType completed_type = 2;</code>
     * @param value The enum numeric value on the wire for completedType to set.
     * @return This builder for chaining.
     */
    public Builder setCompletedTypeValue(int value) {
      
      completedType_ = value;
      onChanged();
      return this;
    }
    /**
     * <code>.temporal.api.enums.v1.QueryResultType completed_type = 2;</code>
     * @return The completedType.
     */
    @java.lang.Override
    public io.temporal.api.enums.v1.QueryResultType getCompletedType() {
      @SuppressWarnings("deprecation")
      io.temporal.api.enums.v1.QueryResultType result = io.temporal.api.enums.v1.QueryResultType.valueOf(completedType_);
      return result == null ? io.temporal.api.enums.v1.QueryResultType.UNRECOGNIZED : result;
    }
    /**
     * <code>.temporal.api.enums.v1.QueryResultType completed_type = 2;</code>
     * @param value The completedType to set.
     * @return This builder for chaining.
     */
    public Builder setCompletedType(io.temporal.api.enums.v1.QueryResultType value) {
      if (value == null) {
        throw new NullPointerException();
      }
      
      completedType_ = value.getNumber();
      onChanged();
      return this;
    }
    /**
     * <code>.temporal.api.enums.v1.QueryResultType completed_type = 2;</code>
     * @return This builder for chaining.
     */
    public Builder clearCompletedType() {
      
      completedType_ = 0;
      onChanged();
      return this;
    }

    private io.temporal.api.common.v1.Payloads queryResult_;
    private com.google.protobuf.SingleFieldBuilderV3<
        io.temporal.api.common.v1.Payloads, io.temporal.api.common.v1.Payloads.Builder, io.temporal.api.common.v1.PayloadsOrBuilder> queryResultBuilder_;
    /**
     * <code>.temporal.api.common.v1.Payloads query_result = 3;</code>
     * @return Whether the queryResult field is set.
     */
    public boolean hasQueryResult() {
      return queryResultBuilder_ != null || queryResult_ != null;
    }
    /**
     * <code>.temporal.api.common.v1.Payloads query_result = 3;</code>
     * @return The queryResult.
     */
    public io.temporal.api.common.v1.Payloads getQueryResult() {
      if (queryResultBuilder_ == null) {
        return queryResult_ == null ? io.temporal.api.common.v1.Payloads.getDefaultInstance() : queryResult_;
      } else {
        return queryResultBuilder_.getMessage();
      }
    }
    /**
     * <code>.temporal.api.common.v1.Payloads query_result = 3;</code>
     */
    public Builder setQueryResult(io.temporal.api.common.v1.Payloads value) {
      if (queryResultBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        queryResult_ = value;
        onChanged();
      } else {
        queryResultBuilder_.setMessage(value);
      }

      return this;
    }
    /**
     * <code>.temporal.api.common.v1.Payloads query_result = 3;</code>
     */
    public Builder setQueryResult(
        io.temporal.api.common.v1.Payloads.Builder builderForValue) {
      if (queryResultBuilder_ == null) {
        queryResult_ = builderForValue.build();
        onChanged();
      } else {
        queryResultBuilder_.setMessage(builderForValue.build());
      }

      return this;
    }
    /**
     * <code>.temporal.api.common.v1.Payloads query_result = 3;</code>
     */
    public Builder mergeQueryResult(io.temporal.api.common.v1.Payloads value) {
      if (queryResultBuilder_ == null) {
        if (queryResult_ != null) {
          queryResult_ =
            io.temporal.api.common.v1.Payloads.newBuilder(queryResult_).mergeFrom(value).buildPartial();
        } else {
          queryResult_ = value;
        }
        onChanged();
      } else {
        queryResultBuilder_.mergeFrom(value);
      }

      return this;
    }
    /**
     * <code>.temporal.api.common.v1.Payloads query_result = 3;</code>
     */
    public Builder clearQueryResult() {
      if (queryResultBuilder_ == null) {
        queryResult_ = null;
        onChanged();
      } else {
        queryResult_ = null;
        queryResultBuilder_ = null;
      }

      return this;
    }
    /**
     * <code>.temporal.api.common.v1.Payloads query_result = 3;</code>
     */
    public io.temporal.api.common.v1.Payloads.Builder getQueryResultBuilder() {
      
      onChanged();
      return getQueryResultFieldBuilder().getBuilder();
    }
    /**
     * <code>.temporal.api.common.v1.Payloads query_result = 3;</code>
     */
    public io.temporal.api.common.v1.PayloadsOrBuilder getQueryResultOrBuilder() {
      if (queryResultBuilder_ != null) {
        return queryResultBuilder_.getMessageOrBuilder();
      } else {
        return queryResult_ == null ?
            io.temporal.api.common.v1.Payloads.getDefaultInstance() : queryResult_;
      }
    }
    /**
     * <code>.temporal.api.common.v1.Payloads query_result = 3;</code>
     */
    private com.google.protobuf.SingleFieldBuilderV3<
        io.temporal.api.common.v1.Payloads, io.temporal.api.common.v1.Payloads.Builder, io.temporal.api.common.v1.PayloadsOrBuilder> 
        getQueryResultFieldBuilder() {
      if (queryResultBuilder_ == null) {
        queryResultBuilder_ = new com.google.protobuf.SingleFieldBuilderV3<
            io.temporal.api.common.v1.Payloads, io.temporal.api.common.v1.Payloads.Builder, io.temporal.api.common.v1.PayloadsOrBuilder>(
                getQueryResult(),
                getParentForChildren(),
                isClean());
        queryResult_ = null;
      }
      return queryResultBuilder_;
    }

    private java.lang.Object errorMessage_ = "";
    /**
     * <code>string error_message = 4;</code>
     * @return The errorMessage.
     */
    public java.lang.String getErrorMessage() {
      java.lang.Object ref = errorMessage_;
      if (!(ref instanceof java.lang.String)) {
        com.google.protobuf.ByteString bs =
            (com.google.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        errorMessage_ = s;
        return s;
      } else {
        return (java.lang.String) ref;
      }
    }
    /**
     * <code>string error_message = 4;</code>
     * @return The bytes for errorMessage.
     */
    public com.google.protobuf.ByteString
        getErrorMessageBytes() {
      java.lang.Object ref = errorMessage_;
      if (ref instanceof String) {
        com.google.protobuf.ByteString b = 
            com.google.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        errorMessage_ = b;
        return b;
      } else {
        return (com.google.protobuf.ByteString) ref;
      }
    }
    /**
     * <code>string error_message = 4;</code>
     * @param value The errorMessage to set.
     * @return This builder for chaining.
     */
    public Builder setErrorMessage(
        java.lang.String value) {
      if (value == null) {
    throw new NullPointerException();
  }
  
      errorMessage_ = value;
      onChanged();
      return this;
    }
    /**
     * <code>string error_message = 4;</code>
     * @return This builder for chaining.
     */
    public Builder clearErrorMessage() {
      
      errorMessage_ = getDefaultInstance().getErrorMessage();
      onChanged();
      return this;
    }
    /**
     * <code>string error_message = 4;</code>
     * @param value The bytes for errorMessage to set.
     * @return This builder for chaining.
     */
    public Builder setErrorMessageBytes(
        com.google.protobuf.ByteString value) {
      if (value == null) {
    throw new NullPointerException();
  }
  checkByteStringIsUtf8(value);
      
      errorMessage_ = value;
      onChanged();
      return this;
    }

    private io.temporal.api.version.v1.WorkerVersionInfo workerVersionInfo_;
    private com.google.protobuf.SingleFieldBuilderV3<
        io.temporal.api.version.v1.WorkerVersionInfo, io.temporal.api.version.v1.WorkerVersionInfo.Builder, io.temporal.api.version.v1.WorkerVersionInfoOrBuilder> workerVersionInfoBuilder_;
    /**
     * <code>.temporal.api.version.v1.WorkerVersionInfo worker_version_info = 5;</code>
     * @return Whether the workerVersionInfo field is set.
     */
    public boolean hasWorkerVersionInfo() {
      return workerVersionInfoBuilder_ != null || workerVersionInfo_ != null;
    }
    /**
     * <code>.temporal.api.version.v1.WorkerVersionInfo worker_version_info = 5;</code>
     * @return The workerVersionInfo.
     */
    public io.temporal.api.version.v1.WorkerVersionInfo getWorkerVersionInfo() {
      if (workerVersionInfoBuilder_ == null) {
        return workerVersionInfo_ == null ? io.temporal.api.version.v1.WorkerVersionInfo.getDefaultInstance() : workerVersionInfo_;
      } else {
        return workerVersionInfoBuilder_.getMessage();
      }
    }
    /**
     * <code>.temporal.api.version.v1.WorkerVersionInfo worker_version_info = 5;</code>
     */
    public Builder setWorkerVersionInfo(io.temporal.api.version.v1.WorkerVersionInfo value) {
      if (workerVersionInfoBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        workerVersionInfo_ = value;
        onChanged();
      } else {
        workerVersionInfoBuilder_.setMessage(value);
      }

      return this;
    }
    /**
     * <code>.temporal.api.version.v1.WorkerVersionInfo worker_version_info = 5;</code>
     */
    public Builder setWorkerVersionInfo(
        io.temporal.api.version.v1.WorkerVersionInfo.Builder builderForValue) {
      if (workerVersionInfoBuilder_ == null) {
        workerVersionInfo_ = builderForValue.build();
        onChanged();
      } else {
        workerVersionInfoBuilder_.setMessage(builderForValue.build());
      }

      return this;
    }
    /**
     * <code>.temporal.api.version.v1.WorkerVersionInfo worker_version_info = 5;</code>
     */
    public Builder mergeWorkerVersionInfo(io.temporal.api.version.v1.WorkerVersionInfo value) {
      if (workerVersionInfoBuilder_ == null) {
        if (workerVersionInfo_ != null) {
          workerVersionInfo_ =
            io.temporal.api.version.v1.WorkerVersionInfo.newBuilder(workerVersionInfo_).mergeFrom(value).buildPartial();
        } else {
          workerVersionInfo_ = value;
        }
        onChanged();
      } else {
        workerVersionInfoBuilder_.mergeFrom(value);
      }

      return this;
    }
    /**
     * <code>.temporal.api.version.v1.WorkerVersionInfo worker_version_info = 5;</code>
     */
    public Builder clearWorkerVersionInfo() {
      if (workerVersionInfoBuilder_ == null) {
        workerVersionInfo_ = null;
        onChanged();
      } else {
        workerVersionInfo_ = null;
        workerVersionInfoBuilder_ = null;
      }

      return this;
    }
    /**
     * <code>.temporal.api.version.v1.WorkerVersionInfo worker_version_info = 5;</code>
     */
    public io.temporal.api.version.v1.WorkerVersionInfo.Builder getWorkerVersionInfoBuilder() {
      
      onChanged();
      return getWorkerVersionInfoFieldBuilder().getBuilder();
    }
    /**
     * <code>.temporal.api.version.v1.WorkerVersionInfo worker_version_info = 5;</code>
     */
    public io.temporal.api.version.v1.WorkerVersionInfoOrBuilder getWorkerVersionInfoOrBuilder() {
      if (workerVersionInfoBuilder_ != null) {
        return workerVersionInfoBuilder_.getMessageOrBuilder();
      } else {
        return workerVersionInfo_ == null ?
            io.temporal.api.version.v1.WorkerVersionInfo.getDefaultInstance() : workerVersionInfo_;
      }
    }
    /**
     * <code>.temporal.api.version.v1.WorkerVersionInfo worker_version_info = 5;</code>
     */
    private com.google.protobuf.SingleFieldBuilderV3<
        io.temporal.api.version.v1.WorkerVersionInfo, io.temporal.api.version.v1.WorkerVersionInfo.Builder, io.temporal.api.version.v1.WorkerVersionInfoOrBuilder> 
        getWorkerVersionInfoFieldBuilder() {
      if (workerVersionInfoBuilder_ == null) {
        workerVersionInfoBuilder_ = new com.google.protobuf.SingleFieldBuilderV3<
            io.temporal.api.version.v1.WorkerVersionInfo, io.temporal.api.version.v1.WorkerVersionInfo.Builder, io.temporal.api.version.v1.WorkerVersionInfoOrBuilder>(
                getWorkerVersionInfo(),
                getParentForChildren(),
                isClean());
        workerVersionInfo_ = null;
      }
      return workerVersionInfoBuilder_;
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


    // @@protoc_insertion_point(builder_scope:temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest)
  }

  // @@protoc_insertion_point(class_scope:temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest)
  private static final io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest();
  }

  public static io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<RespondQueryTaskCompletedRequest>
      PARSER = new com.google.protobuf.AbstractParser<RespondQueryTaskCompletedRequest>() {
    @java.lang.Override
    public RespondQueryTaskCompletedRequest parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new RespondQueryTaskCompletedRequest(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<RespondQueryTaskCompletedRequest> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<RespondQueryTaskCompletedRequest> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

