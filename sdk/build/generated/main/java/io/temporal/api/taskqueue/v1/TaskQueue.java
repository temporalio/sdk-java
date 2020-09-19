// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/taskqueue/v1/message.proto

package io.temporal.api.taskqueue.v1;

/**
 * Protobuf type {@code temporal.api.taskqueue.v1.TaskQueue}
 */
public final class TaskQueue extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:temporal.api.taskqueue.v1.TaskQueue)
    TaskQueueOrBuilder {
private static final long serialVersionUID = 0L;
  // Use TaskQueue.newBuilder() to construct.
  private TaskQueue(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private TaskQueue() {
    name_ = "";
    kind_ = 0;
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new TaskQueue();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private TaskQueue(
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

            name_ = s;
            break;
          }
          case 16: {
            int rawValue = input.readEnum();

            kind_ = rawValue;
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
    return io.temporal.api.taskqueue.v1.MessageProto.internal_static_temporal_api_taskqueue_v1_TaskQueue_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.temporal.api.taskqueue.v1.MessageProto.internal_static_temporal_api_taskqueue_v1_TaskQueue_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.temporal.api.taskqueue.v1.TaskQueue.class, io.temporal.api.taskqueue.v1.TaskQueue.Builder.class);
  }

  public static final int NAME_FIELD_NUMBER = 1;
  private volatile java.lang.Object name_;
  /**
   * <code>string name = 1;</code>
   * @return The name.
   */
  @java.lang.Override
  public java.lang.String getName() {
    java.lang.Object ref = name_;
    if (ref instanceof java.lang.String) {
      return (java.lang.String) ref;
    } else {
      com.google.protobuf.ByteString bs = 
          (com.google.protobuf.ByteString) ref;
      java.lang.String s = bs.toStringUtf8();
      name_ = s;
      return s;
    }
  }
  /**
   * <code>string name = 1;</code>
   * @return The bytes for name.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString
      getNameBytes() {
    java.lang.Object ref = name_;
    if (ref instanceof java.lang.String) {
      com.google.protobuf.ByteString b = 
          com.google.protobuf.ByteString.copyFromUtf8(
              (java.lang.String) ref);
      name_ = b;
      return b;
    } else {
      return (com.google.protobuf.ByteString) ref;
    }
  }

  public static final int KIND_FIELD_NUMBER = 2;
  private int kind_;
  /**
   * <pre>
   * Default: TASK_QUEUE_KIND_NORMAL.
   * </pre>
   *
   * <code>.temporal.api.enums.v1.TaskQueueKind kind = 2;</code>
   * @return The enum numeric value on the wire for kind.
   */
  @java.lang.Override public int getKindValue() {
    return kind_;
  }
  /**
   * <pre>
   * Default: TASK_QUEUE_KIND_NORMAL.
   * </pre>
   *
   * <code>.temporal.api.enums.v1.TaskQueueKind kind = 2;</code>
   * @return The kind.
   */
  @java.lang.Override public io.temporal.api.enums.v1.TaskQueueKind getKind() {
    @SuppressWarnings("deprecation")
    io.temporal.api.enums.v1.TaskQueueKind result = io.temporal.api.enums.v1.TaskQueueKind.valueOf(kind_);
    return result == null ? io.temporal.api.enums.v1.TaskQueueKind.UNRECOGNIZED : result;
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
    if (!getNameBytes().isEmpty()) {
      com.google.protobuf.GeneratedMessageV3.writeString(output, 1, name_);
    }
    if (kind_ != io.temporal.api.enums.v1.TaskQueueKind.TASK_QUEUE_KIND_UNSPECIFIED.getNumber()) {
      output.writeEnum(2, kind_);
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (!getNameBytes().isEmpty()) {
      size += com.google.protobuf.GeneratedMessageV3.computeStringSize(1, name_);
    }
    if (kind_ != io.temporal.api.enums.v1.TaskQueueKind.TASK_QUEUE_KIND_UNSPECIFIED.getNumber()) {
      size += com.google.protobuf.CodedOutputStream
        .computeEnumSize(2, kind_);
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
    if (!(obj instanceof io.temporal.api.taskqueue.v1.TaskQueue)) {
      return super.equals(obj);
    }
    io.temporal.api.taskqueue.v1.TaskQueue other = (io.temporal.api.taskqueue.v1.TaskQueue) obj;

    if (!getName()
        .equals(other.getName())) return false;
    if (kind_ != other.kind_) return false;
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
    hash = (37 * hash) + NAME_FIELD_NUMBER;
    hash = (53 * hash) + getName().hashCode();
    hash = (37 * hash) + KIND_FIELD_NUMBER;
    hash = (53 * hash) + kind_;
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.temporal.api.taskqueue.v1.TaskQueue parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.taskqueue.v1.TaskQueue parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.taskqueue.v1.TaskQueue parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.taskqueue.v1.TaskQueue parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.taskqueue.v1.TaskQueue parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.temporal.api.taskqueue.v1.TaskQueue parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.temporal.api.taskqueue.v1.TaskQueue parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.temporal.api.taskqueue.v1.TaskQueue parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.temporal.api.taskqueue.v1.TaskQueue parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.temporal.api.taskqueue.v1.TaskQueue parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.temporal.api.taskqueue.v1.TaskQueue parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.temporal.api.taskqueue.v1.TaskQueue parseFrom(
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
  public static Builder newBuilder(io.temporal.api.taskqueue.v1.TaskQueue prototype) {
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
   * Protobuf type {@code temporal.api.taskqueue.v1.TaskQueue}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:temporal.api.taskqueue.v1.TaskQueue)
      io.temporal.api.taskqueue.v1.TaskQueueOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.temporal.api.taskqueue.v1.MessageProto.internal_static_temporal_api_taskqueue_v1_TaskQueue_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.temporal.api.taskqueue.v1.MessageProto.internal_static_temporal_api_taskqueue_v1_TaskQueue_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.temporal.api.taskqueue.v1.TaskQueue.class, io.temporal.api.taskqueue.v1.TaskQueue.Builder.class);
    }

    // Construct using io.temporal.api.taskqueue.v1.TaskQueue.newBuilder()
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
      name_ = "";

      kind_ = 0;

      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.temporal.api.taskqueue.v1.MessageProto.internal_static_temporal_api_taskqueue_v1_TaskQueue_descriptor;
    }

    @java.lang.Override
    public io.temporal.api.taskqueue.v1.TaskQueue getDefaultInstanceForType() {
      return io.temporal.api.taskqueue.v1.TaskQueue.getDefaultInstance();
    }

    @java.lang.Override
    public io.temporal.api.taskqueue.v1.TaskQueue build() {
      io.temporal.api.taskqueue.v1.TaskQueue result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.temporal.api.taskqueue.v1.TaskQueue buildPartial() {
      io.temporal.api.taskqueue.v1.TaskQueue result = new io.temporal.api.taskqueue.v1.TaskQueue(this);
      result.name_ = name_;
      result.kind_ = kind_;
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
      if (other instanceof io.temporal.api.taskqueue.v1.TaskQueue) {
        return mergeFrom((io.temporal.api.taskqueue.v1.TaskQueue)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.temporal.api.taskqueue.v1.TaskQueue other) {
      if (other == io.temporal.api.taskqueue.v1.TaskQueue.getDefaultInstance()) return this;
      if (!other.getName().isEmpty()) {
        name_ = other.name_;
        onChanged();
      }
      if (other.kind_ != 0) {
        setKindValue(other.getKindValue());
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
      io.temporal.api.taskqueue.v1.TaskQueue parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.temporal.api.taskqueue.v1.TaskQueue) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private java.lang.Object name_ = "";
    /**
     * <code>string name = 1;</code>
     * @return The name.
     */
    public java.lang.String getName() {
      java.lang.Object ref = name_;
      if (!(ref instanceof java.lang.String)) {
        com.google.protobuf.ByteString bs =
            (com.google.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        name_ = s;
        return s;
      } else {
        return (java.lang.String) ref;
      }
    }
    /**
     * <code>string name = 1;</code>
     * @return The bytes for name.
     */
    public com.google.protobuf.ByteString
        getNameBytes() {
      java.lang.Object ref = name_;
      if (ref instanceof String) {
        com.google.protobuf.ByteString b = 
            com.google.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        name_ = b;
        return b;
      } else {
        return (com.google.protobuf.ByteString) ref;
      }
    }
    /**
     * <code>string name = 1;</code>
     * @param value The name to set.
     * @return This builder for chaining.
     */
    public Builder setName(
        java.lang.String value) {
      if (value == null) {
    throw new NullPointerException();
  }
  
      name_ = value;
      onChanged();
      return this;
    }
    /**
     * <code>string name = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearName() {
      
      name_ = getDefaultInstance().getName();
      onChanged();
      return this;
    }
    /**
     * <code>string name = 1;</code>
     * @param value The bytes for name to set.
     * @return This builder for chaining.
     */
    public Builder setNameBytes(
        com.google.protobuf.ByteString value) {
      if (value == null) {
    throw new NullPointerException();
  }
  checkByteStringIsUtf8(value);
      
      name_ = value;
      onChanged();
      return this;
    }

    private int kind_ = 0;
    /**
     * <pre>
     * Default: TASK_QUEUE_KIND_NORMAL.
     * </pre>
     *
     * <code>.temporal.api.enums.v1.TaskQueueKind kind = 2;</code>
     * @return The enum numeric value on the wire for kind.
     */
    @java.lang.Override public int getKindValue() {
      return kind_;
    }
    /**
     * <pre>
     * Default: TASK_QUEUE_KIND_NORMAL.
     * </pre>
     *
     * <code>.temporal.api.enums.v1.TaskQueueKind kind = 2;</code>
     * @param value The enum numeric value on the wire for kind to set.
     * @return This builder for chaining.
     */
    public Builder setKindValue(int value) {
      
      kind_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * Default: TASK_QUEUE_KIND_NORMAL.
     * </pre>
     *
     * <code>.temporal.api.enums.v1.TaskQueueKind kind = 2;</code>
     * @return The kind.
     */
    @java.lang.Override
    public io.temporal.api.enums.v1.TaskQueueKind getKind() {
      @SuppressWarnings("deprecation")
      io.temporal.api.enums.v1.TaskQueueKind result = io.temporal.api.enums.v1.TaskQueueKind.valueOf(kind_);
      return result == null ? io.temporal.api.enums.v1.TaskQueueKind.UNRECOGNIZED : result;
    }
    /**
     * <pre>
     * Default: TASK_QUEUE_KIND_NORMAL.
     * </pre>
     *
     * <code>.temporal.api.enums.v1.TaskQueueKind kind = 2;</code>
     * @param value The kind to set.
     * @return This builder for chaining.
     */
    public Builder setKind(io.temporal.api.enums.v1.TaskQueueKind value) {
      if (value == null) {
        throw new NullPointerException();
      }
      
      kind_ = value.getNumber();
      onChanged();
      return this;
    }
    /**
     * <pre>
     * Default: TASK_QUEUE_KIND_NORMAL.
     * </pre>
     *
     * <code>.temporal.api.enums.v1.TaskQueueKind kind = 2;</code>
     * @return This builder for chaining.
     */
    public Builder clearKind() {
      
      kind_ = 0;
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


    // @@protoc_insertion_point(builder_scope:temporal.api.taskqueue.v1.TaskQueue)
  }

  // @@protoc_insertion_point(class_scope:temporal.api.taskqueue.v1.TaskQueue)
  private static final io.temporal.api.taskqueue.v1.TaskQueue DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.temporal.api.taskqueue.v1.TaskQueue();
  }

  public static io.temporal.api.taskqueue.v1.TaskQueue getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<TaskQueue>
      PARSER = new com.google.protobuf.AbstractParser<TaskQueue>() {
    @java.lang.Override
    public TaskQueue parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new TaskQueue(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<TaskQueue> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<TaskQueue> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.temporal.api.taskqueue.v1.TaskQueue getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

