// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/history/v1/message.proto

package io.temporal.api.history.v1;

public interface WorkflowExecutionTerminatedEventAttributesOrBuilder extends
    // @@protoc_insertion_point(interface_extends:temporal.api.history.v1.WorkflowExecutionTerminatedEventAttributes)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>string reason = 1;</code>
   * @return The reason.
   */
  java.lang.String getReason();
  /**
   * <code>string reason = 1;</code>
   * @return The bytes for reason.
   */
  com.google.protobuf.ByteString
      getReasonBytes();

  /**
   * <code>.temporal.api.common.v1.Payloads details = 2;</code>
   * @return Whether the details field is set.
   */
  boolean hasDetails();
  /**
   * <code>.temporal.api.common.v1.Payloads details = 2;</code>
   * @return The details.
   */
  io.temporal.api.common.v1.Payloads getDetails();
  /**
   * <code>.temporal.api.common.v1.Payloads details = 2;</code>
   */
  io.temporal.api.common.v1.PayloadsOrBuilder getDetailsOrBuilder();

  /**
   * <code>string identity = 3;</code>
   * @return The identity.
   */
  java.lang.String getIdentity();
  /**
   * <code>string identity = 3;</code>
   * @return The bytes for identity.
   */
  com.google.protobuf.ByteString
      getIdentityBytes();
}
