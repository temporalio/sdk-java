// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/workflowservice/v1/request_response.proto

package io.temporal.api.workflowservice.v1;

/**
 * Protobuf type {@code temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse}
 */
public final class RequestCancelWorkflowExecutionResponse extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse)
    RequestCancelWorkflowExecutionResponseOrBuilder {
private static final long serialVersionUID = 0L;
  // Use RequestCancelWorkflowExecutionResponse.newBuilder() to construct.
  private RequestCancelWorkflowExecutionResponse(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private RequestCancelWorkflowExecutionResponse() {
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new RequestCancelWorkflowExecutionResponse();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private RequestCancelWorkflowExecutionResponse(
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
    return io.temporal.api.workflowservice.v1.RequestResponseProto.internal_static_temporal_api_workflowservice_v1_RequestCancelWorkflowExecutionResponse_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.temporal.api.workflowservice.v1.RequestResponseProto.internal_static_temporal_api_workflowservice_v1_RequestCancelWorkflowExecutionResponse_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse.class, io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse.Builder.class);
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
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    size += unknownFields.getSerializedSize();
    memoizedSize = size;
    return size;
  }

  @java.lang.Override
  public boolean equals(final java.lang.Object obj) {
    if (obj == this) {
     return true;
    }
    if (!(obj instanceof io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse)) {
      return super.equals(obj);
    }
    io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse other = (io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse) obj;

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
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse parseFrom(
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
  public static Builder newBuilder(io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse prototype) {
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
   * Protobuf type {@code temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse)
      io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponseOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.temporal.api.workflowservice.v1.RequestResponseProto.internal_static_temporal_api_workflowservice_v1_RequestCancelWorkflowExecutionResponse_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.temporal.api.workflowservice.v1.RequestResponseProto.internal_static_temporal_api_workflowservice_v1_RequestCancelWorkflowExecutionResponse_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse.class, io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse.Builder.class);
    }

    // Construct using io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse.newBuilder()
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
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.temporal.api.workflowservice.v1.RequestResponseProto.internal_static_temporal_api_workflowservice_v1_RequestCancelWorkflowExecutionResponse_descriptor;
    }

    @java.lang.Override
    public io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse getDefaultInstanceForType() {
      return io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse.getDefaultInstance();
    }

    @java.lang.Override
    public io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse build() {
      io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse buildPartial() {
      io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse result = new io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse(this);
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
      if (other instanceof io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse) {
        return mergeFrom((io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse other) {
      if (other == io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse.getDefaultInstance()) return this;
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
      io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
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


    // @@protoc_insertion_point(builder_scope:temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse)
  }

  // @@protoc_insertion_point(class_scope:temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse)
  private static final io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse();
  }

  public static io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<RequestCancelWorkflowExecutionResponse>
      PARSER = new com.google.protobuf.AbstractParser<RequestCancelWorkflowExecutionResponse>() {
    @java.lang.Override
    public RequestCancelWorkflowExecutionResponse parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new RequestCancelWorkflowExecutionResponse(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<RequestCancelWorkflowExecutionResponse> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<RequestCancelWorkflowExecutionResponse> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

