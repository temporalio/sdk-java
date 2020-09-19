// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/workflowservice/v1/request_response.proto

package io.temporal.api.workflowservice.v1;

public interface PollActivityTaskQueueResponseOrBuilder extends
    // @@protoc_insertion_point(interface_extends:temporal.api.workflowservice.v1.PollActivityTaskQueueResponse)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>bytes task_token = 1;</code>
   * @return The taskToken.
   */
  com.google.protobuf.ByteString getTaskToken();

  /**
   * <code>string workflow_namespace = 2;</code>
   * @return The workflowNamespace.
   */
  java.lang.String getWorkflowNamespace();
  /**
   * <code>string workflow_namespace = 2;</code>
   * @return The bytes for workflowNamespace.
   */
  com.google.protobuf.ByteString
      getWorkflowNamespaceBytes();

  /**
   * <code>.temporal.api.common.v1.WorkflowType workflow_type = 3;</code>
   * @return Whether the workflowType field is set.
   */
  boolean hasWorkflowType();
  /**
   * <code>.temporal.api.common.v1.WorkflowType workflow_type = 3;</code>
   * @return The workflowType.
   */
  io.temporal.api.common.v1.WorkflowType getWorkflowType();
  /**
   * <code>.temporal.api.common.v1.WorkflowType workflow_type = 3;</code>
   */
  io.temporal.api.common.v1.WorkflowTypeOrBuilder getWorkflowTypeOrBuilder();

  /**
   * <code>.temporal.api.common.v1.WorkflowExecution workflow_execution = 4;</code>
   * @return Whether the workflowExecution field is set.
   */
  boolean hasWorkflowExecution();
  /**
   * <code>.temporal.api.common.v1.WorkflowExecution workflow_execution = 4;</code>
   * @return The workflowExecution.
   */
  io.temporal.api.common.v1.WorkflowExecution getWorkflowExecution();
  /**
   * <code>.temporal.api.common.v1.WorkflowExecution workflow_execution = 4;</code>
   */
  io.temporal.api.common.v1.WorkflowExecutionOrBuilder getWorkflowExecutionOrBuilder();

  /**
   * <code>.temporal.api.common.v1.ActivityType activity_type = 5;</code>
   * @return Whether the activityType field is set.
   */
  boolean hasActivityType();
  /**
   * <code>.temporal.api.common.v1.ActivityType activity_type = 5;</code>
   * @return The activityType.
   */
  io.temporal.api.common.v1.ActivityType getActivityType();
  /**
   * <code>.temporal.api.common.v1.ActivityType activity_type = 5;</code>
   */
  io.temporal.api.common.v1.ActivityTypeOrBuilder getActivityTypeOrBuilder();

  /**
   * <code>string activity_id = 6;</code>
   * @return The activityId.
   */
  java.lang.String getActivityId();
  /**
   * <code>string activity_id = 6;</code>
   * @return The bytes for activityId.
   */
  com.google.protobuf.ByteString
      getActivityIdBytes();

  /**
   * <code>.temporal.api.common.v1.Header header = 7;</code>
   * @return Whether the header field is set.
   */
  boolean hasHeader();
  /**
   * <code>.temporal.api.common.v1.Header header = 7;</code>
   * @return The header.
   */
  io.temporal.api.common.v1.Header getHeader();
  /**
   * <code>.temporal.api.common.v1.Header header = 7;</code>
   */
  io.temporal.api.common.v1.HeaderOrBuilder getHeaderOrBuilder();

  /**
   * <code>.temporal.api.common.v1.Payloads input = 8;</code>
   * @return Whether the input field is set.
   */
  boolean hasInput();
  /**
   * <code>.temporal.api.common.v1.Payloads input = 8;</code>
   * @return The input.
   */
  io.temporal.api.common.v1.Payloads getInput();
  /**
   * <code>.temporal.api.common.v1.Payloads input = 8;</code>
   */
  io.temporal.api.common.v1.PayloadsOrBuilder getInputOrBuilder();

  /**
   * <code>.temporal.api.common.v1.Payloads heartbeat_details = 9;</code>
   * @return Whether the heartbeatDetails field is set.
   */
  boolean hasHeartbeatDetails();
  /**
   * <code>.temporal.api.common.v1.Payloads heartbeat_details = 9;</code>
   * @return The heartbeatDetails.
   */
  io.temporal.api.common.v1.Payloads getHeartbeatDetails();
  /**
   * <code>.temporal.api.common.v1.Payloads heartbeat_details = 9;</code>
   */
  io.temporal.api.common.v1.PayloadsOrBuilder getHeartbeatDetailsOrBuilder();

  /**
   * <code>.google.protobuf.Timestamp scheduled_time = 10 [(.gogoproto.stdtime) = true];</code>
   * @return Whether the scheduledTime field is set.
   */
  boolean hasScheduledTime();
  /**
   * <code>.google.protobuf.Timestamp scheduled_time = 10 [(.gogoproto.stdtime) = true];</code>
   * @return The scheduledTime.
   */
  com.google.protobuf.Timestamp getScheduledTime();
  /**
   * <code>.google.protobuf.Timestamp scheduled_time = 10 [(.gogoproto.stdtime) = true];</code>
   */
  com.google.protobuf.TimestampOrBuilder getScheduledTimeOrBuilder();

  /**
   * <code>.google.protobuf.Timestamp current_attempt_scheduled_time = 11 [(.gogoproto.stdtime) = true];</code>
   * @return Whether the currentAttemptScheduledTime field is set.
   */
  boolean hasCurrentAttemptScheduledTime();
  /**
   * <code>.google.protobuf.Timestamp current_attempt_scheduled_time = 11 [(.gogoproto.stdtime) = true];</code>
   * @return The currentAttemptScheduledTime.
   */
  com.google.protobuf.Timestamp getCurrentAttemptScheduledTime();
  /**
   * <code>.google.protobuf.Timestamp current_attempt_scheduled_time = 11 [(.gogoproto.stdtime) = true];</code>
   */
  com.google.protobuf.TimestampOrBuilder getCurrentAttemptScheduledTimeOrBuilder();

  /**
   * <code>.google.protobuf.Timestamp started_time = 12 [(.gogoproto.stdtime) = true];</code>
   * @return Whether the startedTime field is set.
   */
  boolean hasStartedTime();
  /**
   * <code>.google.protobuf.Timestamp started_time = 12 [(.gogoproto.stdtime) = true];</code>
   * @return The startedTime.
   */
  com.google.protobuf.Timestamp getStartedTime();
  /**
   * <code>.google.protobuf.Timestamp started_time = 12 [(.gogoproto.stdtime) = true];</code>
   */
  com.google.protobuf.TimestampOrBuilder getStartedTimeOrBuilder();

  /**
   * <code>int32 attempt = 13;</code>
   * @return The attempt.
   */
  int getAttempt();

  /**
   * <pre>
   * (-- api-linter: core::0140::prepositions=disabled
   *     aip.dev/not-precedent: "to" is used to indicate interval. --)
   * </pre>
   *
   * <code>.google.protobuf.Duration schedule_to_close_timeout = 14 [(.gogoproto.stdduration) = true];</code>
   * @return Whether the scheduleToCloseTimeout field is set.
   */
  boolean hasScheduleToCloseTimeout();
  /**
   * <pre>
   * (-- api-linter: core::0140::prepositions=disabled
   *     aip.dev/not-precedent: "to" is used to indicate interval. --)
   * </pre>
   *
   * <code>.google.protobuf.Duration schedule_to_close_timeout = 14 [(.gogoproto.stdduration) = true];</code>
   * @return The scheduleToCloseTimeout.
   */
  com.google.protobuf.Duration getScheduleToCloseTimeout();
  /**
   * <pre>
   * (-- api-linter: core::0140::prepositions=disabled
   *     aip.dev/not-precedent: "to" is used to indicate interval. --)
   * </pre>
   *
   * <code>.google.protobuf.Duration schedule_to_close_timeout = 14 [(.gogoproto.stdduration) = true];</code>
   */
  com.google.protobuf.DurationOrBuilder getScheduleToCloseTimeoutOrBuilder();

  /**
   * <pre>
   * (-- api-linter: core::0140::prepositions=disabled
   *     aip.dev/not-precedent: "to" is used to indicate interval. --)
   * </pre>
   *
   * <code>.google.protobuf.Duration start_to_close_timeout = 15 [(.gogoproto.stdduration) = true];</code>
   * @return Whether the startToCloseTimeout field is set.
   */
  boolean hasStartToCloseTimeout();
  /**
   * <pre>
   * (-- api-linter: core::0140::prepositions=disabled
   *     aip.dev/not-precedent: "to" is used to indicate interval. --)
   * </pre>
   *
   * <code>.google.protobuf.Duration start_to_close_timeout = 15 [(.gogoproto.stdduration) = true];</code>
   * @return The startToCloseTimeout.
   */
  com.google.protobuf.Duration getStartToCloseTimeout();
  /**
   * <pre>
   * (-- api-linter: core::0140::prepositions=disabled
   *     aip.dev/not-precedent: "to" is used to indicate interval. --)
   * </pre>
   *
   * <code>.google.protobuf.Duration start_to_close_timeout = 15 [(.gogoproto.stdduration) = true];</code>
   */
  com.google.protobuf.DurationOrBuilder getStartToCloseTimeoutOrBuilder();

  /**
   * <code>.google.protobuf.Duration heartbeat_timeout = 16 [(.gogoproto.stdduration) = true];</code>
   * @return Whether the heartbeatTimeout field is set.
   */
  boolean hasHeartbeatTimeout();
  /**
   * <code>.google.protobuf.Duration heartbeat_timeout = 16 [(.gogoproto.stdduration) = true];</code>
   * @return The heartbeatTimeout.
   */
  com.google.protobuf.Duration getHeartbeatTimeout();
  /**
   * <code>.google.protobuf.Duration heartbeat_timeout = 16 [(.gogoproto.stdduration) = true];</code>
   */
  com.google.protobuf.DurationOrBuilder getHeartbeatTimeoutOrBuilder();

  /**
   * <pre>
   * This is an actual retry policy the service uses.
   * It can be different from the one provided (or not) during activity scheduling
   * as the service can override the provided one in case its values are not specified
   * or exceed configured system limits.
   * </pre>
   *
   * <code>.temporal.api.common.v1.RetryPolicy retry_policy = 17;</code>
   * @return Whether the retryPolicy field is set.
   */
  boolean hasRetryPolicy();
  /**
   * <pre>
   * This is an actual retry policy the service uses.
   * It can be different from the one provided (or not) during activity scheduling
   * as the service can override the provided one in case its values are not specified
   * or exceed configured system limits.
   * </pre>
   *
   * <code>.temporal.api.common.v1.RetryPolicy retry_policy = 17;</code>
   * @return The retryPolicy.
   */
  io.temporal.api.common.v1.RetryPolicy getRetryPolicy();
  /**
   * <pre>
   * This is an actual retry policy the service uses.
   * It can be different from the one provided (or not) during activity scheduling
   * as the service can override the provided one in case its values are not specified
   * or exceed configured system limits.
   * </pre>
   *
   * <code>.temporal.api.common.v1.RetryPolicy retry_policy = 17;</code>
   */
  io.temporal.api.common.v1.RetryPolicyOrBuilder getRetryPolicyOrBuilder();
}
