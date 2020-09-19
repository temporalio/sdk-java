// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/workflowservice/v1/request_response.proto

package io.temporal.api.workflowservice.v1;

public interface RecordActivityTaskHeartbeatByIdRequestOrBuilder extends
    // @@protoc_insertion_point(interface_extends:temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdRequest)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>string namespace = 1;</code>
   * @return The namespace.
   */
  java.lang.String getNamespace();
  /**
   * <code>string namespace = 1;</code>
   * @return The bytes for namespace.
   */
  com.google.protobuf.ByteString
      getNamespaceBytes();

  /**
   * <code>string workflow_id = 2;</code>
   * @return The workflowId.
   */
  java.lang.String getWorkflowId();
  /**
   * <code>string workflow_id = 2;</code>
   * @return The bytes for workflowId.
   */
  com.google.protobuf.ByteString
      getWorkflowIdBytes();

  /**
   * <code>string run_id = 3;</code>
   * @return The runId.
   */
  java.lang.String getRunId();
  /**
   * <code>string run_id = 3;</code>
   * @return The bytes for runId.
   */
  com.google.protobuf.ByteString
      getRunIdBytes();

  /**
   * <code>string activity_id = 4;</code>
   * @return The activityId.
   */
  java.lang.String getActivityId();
  /**
   * <code>string activity_id = 4;</code>
   * @return The bytes for activityId.
   */
  com.google.protobuf.ByteString
      getActivityIdBytes();

  /**
   * <code>.temporal.api.common.v1.Payloads details = 5;</code>
   * @return Whether the details field is set.
   */
  boolean hasDetails();
  /**
   * <code>.temporal.api.common.v1.Payloads details = 5;</code>
   * @return The details.
   */
  io.temporal.api.common.v1.Payloads getDetails();
  /**
   * <code>.temporal.api.common.v1.Payloads details = 5;</code>
   */
  io.temporal.api.common.v1.PayloadsOrBuilder getDetailsOrBuilder();

  /**
   * <code>string identity = 6;</code>
   * @return The identity.
   */
  java.lang.String getIdentity();
  /**
   * <code>string identity = 6;</code>
   * @return The bytes for identity.
   */
  com.google.protobuf.ByteString
      getIdentityBytes();
}
