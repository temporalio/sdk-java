// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/workflowservice/v1/request_response.proto

package io.temporal.api.workflowservice.v1;

public interface RecordActivityTaskHeartbeatRequestOrBuilder extends
    // @@protoc_insertion_point(interface_extends:temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>bytes task_token = 1;</code>
   * @return The taskToken.
   */
  com.google.protobuf.ByteString getTaskToken();

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
