// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/history/v1/message.proto

package io.temporal.api.history.v1;

public interface ActivityTaskTimedOutEventAttributesOrBuilder extends
    // @@protoc_insertion_point(interface_extends:temporal.api.history.v1.ActivityTaskTimedOutEventAttributes)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * For retry activity, it may have a failure before timeout. It is stored as `cause` in `failure`.
   * </pre>
   *
   * <code>.temporal.api.failure.v1.Failure failure = 1;</code>
   * @return Whether the failure field is set.
   */
  boolean hasFailure();
  /**
   * <pre>
   * For retry activity, it may have a failure before timeout. It is stored as `cause` in `failure`.
   * </pre>
   *
   * <code>.temporal.api.failure.v1.Failure failure = 1;</code>
   * @return The failure.
   */
  io.temporal.api.failure.v1.Failure getFailure();
  /**
   * <pre>
   * For retry activity, it may have a failure before timeout. It is stored as `cause` in `failure`.
   * </pre>
   *
   * <code>.temporal.api.failure.v1.Failure failure = 1;</code>
   */
  io.temporal.api.failure.v1.FailureOrBuilder getFailureOrBuilder();

  /**
   * <code>int64 scheduled_event_id = 2;</code>
   * @return The scheduledEventId.
   */
  long getScheduledEventId();

  /**
   * <code>int64 started_event_id = 3;</code>
   * @return The startedEventId.
   */
  long getStartedEventId();

  /**
   * <code>.temporal.api.enums.v1.RetryState retry_state = 4;</code>
   * @return The enum numeric value on the wire for retryState.
   */
  int getRetryStateValue();
  /**
   * <code>.temporal.api.enums.v1.RetryState retry_state = 4;</code>
   * @return The retryState.
   */
  io.temporal.api.enums.v1.RetryState getRetryState();
}
