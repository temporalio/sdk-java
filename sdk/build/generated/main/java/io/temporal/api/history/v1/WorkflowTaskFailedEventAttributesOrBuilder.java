// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/history/v1/message.proto

package io.temporal.api.history.v1;

public interface WorkflowTaskFailedEventAttributesOrBuilder extends
    // @@protoc_insertion_point(interface_extends:temporal.api.history.v1.WorkflowTaskFailedEventAttributes)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>int64 scheduled_event_id = 1;</code>
   * @return The scheduledEventId.
   */
  long getScheduledEventId();

  /**
   * <code>int64 started_event_id = 2;</code>
   * @return The startedEventId.
   */
  long getStartedEventId();

  /**
   * <code>.temporal.api.enums.v1.WorkflowTaskFailedCause cause = 3;</code>
   * @return The enum numeric value on the wire for cause.
   */
  int getCauseValue();
  /**
   * <code>.temporal.api.enums.v1.WorkflowTaskFailedCause cause = 3;</code>
   * @return The cause.
   */
  io.temporal.api.enums.v1.WorkflowTaskFailedCause getCause();

  /**
   * <code>.temporal.api.failure.v1.Failure failure = 4;</code>
   * @return Whether the failure field is set.
   */
  boolean hasFailure();
  /**
   * <code>.temporal.api.failure.v1.Failure failure = 4;</code>
   * @return The failure.
   */
  io.temporal.api.failure.v1.Failure getFailure();
  /**
   * <code>.temporal.api.failure.v1.Failure failure = 4;</code>
   */
  io.temporal.api.failure.v1.FailureOrBuilder getFailureOrBuilder();

  /**
   * <code>string identity = 5;</code>
   * @return The identity.
   */
  java.lang.String getIdentity();
  /**
   * <code>string identity = 5;</code>
   * @return The bytes for identity.
   */
  com.google.protobuf.ByteString
      getIdentityBytes();

  /**
   * <pre>
   * For reset workflow.
   * </pre>
   *
   * <code>string base_run_id = 6;</code>
   * @return The baseRunId.
   */
  java.lang.String getBaseRunId();
  /**
   * <pre>
   * For reset workflow.
   * </pre>
   *
   * <code>string base_run_id = 6;</code>
   * @return The bytes for baseRunId.
   */
  com.google.protobuf.ByteString
      getBaseRunIdBytes();

  /**
   * <code>string new_run_id = 7;</code>
   * @return The newRunId.
   */
  java.lang.String getNewRunId();
  /**
   * <code>string new_run_id = 7;</code>
   * @return The bytes for newRunId.
   */
  com.google.protobuf.ByteString
      getNewRunIdBytes();

  /**
   * <code>int64 fork_event_version = 8;</code>
   * @return The forkEventVersion.
   */
  long getForkEventVersion();

  /**
   * <code>string binary_checksum = 9;</code>
   * @return The binaryChecksum.
   */
  java.lang.String getBinaryChecksum();
  /**
   * <code>string binary_checksum = 9;</code>
   * @return The bytes for binaryChecksum.
   */
  com.google.protobuf.ByteString
      getBinaryChecksumBytes();
}
