// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/workflowservice/v1/request_response.proto

package io.temporal.api.workflowservice.v1;

/**
 * Protobuf type {@code temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse}
 */
public final class RespondWorkflowTaskCompletedResponse extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse)
    RespondWorkflowTaskCompletedResponseOrBuilder {
private static final long serialVersionUID = 0L;
  // Use RespondWorkflowTaskCompletedResponse.newBuilder() to construct.
  private RespondWorkflowTaskCompletedResponse(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private RespondWorkflowTaskCompletedResponse() {
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new RespondWorkflowTaskCompletedResponse();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private RespondWorkflowTaskCompletedResponse(
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
            io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse.Builder subBuilder = null;
            if (workflowTask_ != null) {
              subBuilder = workflowTask_.toBuilder();
            }
            workflowTask_ = input.readMessage(io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse.parser(), extensionRegistry);
            if (subBuilder != null) {
              subBuilder.mergeFrom(workflowTask_);
              workflowTask_ = subBuilder.buildPartial();
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
    return io.temporal.api.workflowservice.v1.RequestResponseProto.internal_static_temporal_api_workflowservice_v1_RespondWorkflowTaskCompletedResponse_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.temporal.api.workflowservice.v1.RequestResponseProto.internal_static_temporal_api_workflowservice_v1_RespondWorkflowTaskCompletedResponse_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse.class, io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse.Builder.class);
  }

  public static final int WORKFLOW_TASK_FIELD_NUMBER = 1;
  private io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse workflowTask_;
  /**
   * <code>.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse workflow_task = 1;</code>
   * @return Whether the workflowTask field is set.
   */
  @java.lang.Override
  public boolean hasWorkflowTask() {
    return workflowTask_ != null;
  }
  /**
   * <code>.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse workflow_task = 1;</code>
   * @return The workflowTask.
   */
  @java.lang.Override
  public io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse getWorkflowTask() {
    return workflowTask_ == null ? io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse.getDefaultInstance() : workflowTask_;
  }
  /**
   * <code>.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse workflow_task = 1;</code>
   */
  @java.lang.Override
  public io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder getWorkflowTaskOrBuilder() {
    return getWorkflowTask();
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
    if (workflowTask_ != null) {
      output.writeMessage(1, getWorkflowTask());
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (workflowTask_ != null) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(1, getWorkflowTask());
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
    if (!(obj instanceof io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse)) {
      return super.equals(obj);
    }
    io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse other = (io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse) obj;

    if (hasWorkflowTask() != other.hasWorkflowTask()) return false;
    if (hasWorkflowTask()) {
      if (!getWorkflowTask()
          .equals(other.getWorkflowTask())) return false;
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
    if (hasWorkflowTask()) {
      hash = (37 * hash) + WORKFLOW_TASK_FIELD_NUMBER;
      hash = (53 * hash) + getWorkflowTask().hashCode();
    }
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse parseFrom(
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
  public static Builder newBuilder(io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse prototype) {
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
   * Protobuf type {@code temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse)
      io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponseOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.temporal.api.workflowservice.v1.RequestResponseProto.internal_static_temporal_api_workflowservice_v1_RespondWorkflowTaskCompletedResponse_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.temporal.api.workflowservice.v1.RequestResponseProto.internal_static_temporal_api_workflowservice_v1_RespondWorkflowTaskCompletedResponse_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse.class, io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse.Builder.class);
    }

    // Construct using io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse.newBuilder()
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
      if (workflowTaskBuilder_ == null) {
        workflowTask_ = null;
      } else {
        workflowTask_ = null;
        workflowTaskBuilder_ = null;
      }
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.temporal.api.workflowservice.v1.RequestResponseProto.internal_static_temporal_api_workflowservice_v1_RespondWorkflowTaskCompletedResponse_descriptor;
    }

    @java.lang.Override
    public io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse getDefaultInstanceForType() {
      return io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse.getDefaultInstance();
    }

    @java.lang.Override
    public io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse build() {
      io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse buildPartial() {
      io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse result = new io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse(this);
      if (workflowTaskBuilder_ == null) {
        result.workflowTask_ = workflowTask_;
      } else {
        result.workflowTask_ = workflowTaskBuilder_.build();
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
      if (other instanceof io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse) {
        return mergeFrom((io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse other) {
      if (other == io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse.getDefaultInstance()) return this;
      if (other.hasWorkflowTask()) {
        mergeWorkflowTask(other.getWorkflowTask());
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
      io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse workflowTask_;
    private com.google.protobuf.SingleFieldBuilderV3<
        io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse, io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse.Builder, io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder> workflowTaskBuilder_;
    /**
     * <code>.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse workflow_task = 1;</code>
     * @return Whether the workflowTask field is set.
     */
    public boolean hasWorkflowTask() {
      return workflowTaskBuilder_ != null || workflowTask_ != null;
    }
    /**
     * <code>.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse workflow_task = 1;</code>
     * @return The workflowTask.
     */
    public io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse getWorkflowTask() {
      if (workflowTaskBuilder_ == null) {
        return workflowTask_ == null ? io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse.getDefaultInstance() : workflowTask_;
      } else {
        return workflowTaskBuilder_.getMessage();
      }
    }
    /**
     * <code>.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse workflow_task = 1;</code>
     */
    public Builder setWorkflowTask(io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse value) {
      if (workflowTaskBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        workflowTask_ = value;
        onChanged();
      } else {
        workflowTaskBuilder_.setMessage(value);
      }

      return this;
    }
    /**
     * <code>.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse workflow_task = 1;</code>
     */
    public Builder setWorkflowTask(
        io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse.Builder builderForValue) {
      if (workflowTaskBuilder_ == null) {
        workflowTask_ = builderForValue.build();
        onChanged();
      } else {
        workflowTaskBuilder_.setMessage(builderForValue.build());
      }

      return this;
    }
    /**
     * <code>.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse workflow_task = 1;</code>
     */
    public Builder mergeWorkflowTask(io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse value) {
      if (workflowTaskBuilder_ == null) {
        if (workflowTask_ != null) {
          workflowTask_ =
            io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse.newBuilder(workflowTask_).mergeFrom(value).buildPartial();
        } else {
          workflowTask_ = value;
        }
        onChanged();
      } else {
        workflowTaskBuilder_.mergeFrom(value);
      }

      return this;
    }
    /**
     * <code>.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse workflow_task = 1;</code>
     */
    public Builder clearWorkflowTask() {
      if (workflowTaskBuilder_ == null) {
        workflowTask_ = null;
        onChanged();
      } else {
        workflowTask_ = null;
        workflowTaskBuilder_ = null;
      }

      return this;
    }
    /**
     * <code>.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse workflow_task = 1;</code>
     */
    public io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse.Builder getWorkflowTaskBuilder() {
      
      onChanged();
      return getWorkflowTaskFieldBuilder().getBuilder();
    }
    /**
     * <code>.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse workflow_task = 1;</code>
     */
    public io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder getWorkflowTaskOrBuilder() {
      if (workflowTaskBuilder_ != null) {
        return workflowTaskBuilder_.getMessageOrBuilder();
      } else {
        return workflowTask_ == null ?
            io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse.getDefaultInstance() : workflowTask_;
      }
    }
    /**
     * <code>.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse workflow_task = 1;</code>
     */
    private com.google.protobuf.SingleFieldBuilderV3<
        io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse, io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse.Builder, io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder> 
        getWorkflowTaskFieldBuilder() {
      if (workflowTaskBuilder_ == null) {
        workflowTaskBuilder_ = new com.google.protobuf.SingleFieldBuilderV3<
            io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse, io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse.Builder, io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder>(
                getWorkflowTask(),
                getParentForChildren(),
                isClean());
        workflowTask_ = null;
      }
      return workflowTaskBuilder_;
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


    // @@protoc_insertion_point(builder_scope:temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse)
  }

  // @@protoc_insertion_point(class_scope:temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse)
  private static final io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse();
  }

  public static io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<RespondWorkflowTaskCompletedResponse>
      PARSER = new com.google.protobuf.AbstractParser<RespondWorkflowTaskCompletedResponse>() {
    @java.lang.Override
    public RespondWorkflowTaskCompletedResponse parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new RespondWorkflowTaskCompletedResponse(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<RespondWorkflowTaskCompletedResponse> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<RespondWorkflowTaskCompletedResponse> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

