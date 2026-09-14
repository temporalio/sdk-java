package io.temporal.client;

import io.temporal.activity.ActivityExecutionContext;
import io.temporal.common.Experimental;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.PayloadConverter;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.payload.context.SerializationContext;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Used to complete asynchronously activities that called {@link
 * ActivityExecutionContext#doNotCompleteOnReturn()}.
 *
 * <p>Use {@link WorkflowClient#newActivityCompletionClient()} to create an instance.
 */
public interface ActivityCompletionClient {

  /**
   * Completes an activity execution successfully using a task token.
   *
   * <p>This overload works with both workflow activities and standalone activities.
   *
   * @param taskToken token of the activity attempt to complete
   * @param result of the activity execution
   */
  <R> void complete(byte[] taskToken, R result) throws ActivityCompletionException;

  /**
   * Completes a workflow activity execution successfully using workflow and activity IDs.
   *
   * <p>This overload is only for workflow activities. To complete a standalone activity by ID, use
   * {@link #completeStandalone(String, Optional, Object)}.
   *
   * @param workflowId id of the workflow that started the activity
   * @param runId optional run id of the workflow that started the activity
   * @param activityId id of the activity
   * @param result of the activity execution
   */
  <R> void complete(String workflowId, Optional<String> runId, String activityId, R result)
      throws ActivityCompletionException;

  /**
   * Completes a standalone activity execution successfully using activity ID.
   *
   * <p>This method is only for standalone activities. To complete a workflow activity by ID, use
   * {@link #complete(String, Optional, String, Object)}.
   *
   * @param activityId id of the standalone activity
   * @param activityRunId optional run id of the standalone activity, or {@code Optional.empty()}
   * @param result of the activity execution
   */
  <R> void completeStandalone(String activityId, Optional<String> activityRunId, R result)
      throws ActivityCompletionException;

  /**
   * Completes an activity execution with failure using a task token.
   *
   * <p>This overload works with both workflow activities and standalone activities.
   *
   * @param taskToken token of the activity attempt to complete
   * @param result the exception to be used as a failure details object
   */
  void completeExceptionally(byte[] taskToken, Exception result) throws ActivityCompletionException;

  /**
   * Completes a workflow activity execution with failure using workflow and activity IDs.
   *
   * <p>This overload is only for workflow activities. To complete a standalone activity by ID, use
   * {@link #completeExceptionallyStandalone(String, Optional, Exception)}.
   *
   * @param workflowId id of the workflow that started the activity
   * @param runId optional run id of the workflow that started the activity
   * @param activityId id of the activity
   * @param result the exception to be used as a failure details object
   */
  void completeExceptionally(
      String workflowId, Optional<String> runId, String activityId, Exception result)
      throws ActivityCompletionException;

  /**
   * Completes a standalone activity execution with failure using activity ID.
   *
   * <p>This method is only for standalone activities. To complete a workflow activity by ID, use
   * {@link #completeExceptionally(String, Optional, String, Exception)}.
   *
   * @param activityId id of the standalone activity
   * @param activityRunId optional run id of the standalone activity, or {@code Optional.empty()}
   * @param result the exception to be used as a failure details object
   */
  void completeExceptionallyStandalone(
      String activityId, Optional<String> activityRunId, Exception result)
      throws ActivityCompletionException;

  /**
   * Confirms successful cancellation to the server using a task token.
   *
   * <p>This overload works with both workflow activities and standalone activities.
   *
   * @param taskToken token of the activity attempt
   * @param details details to record with the cancellation
   */
  <V> void reportCancellation(byte[] taskToken, V details) throws ActivityCompletionException;

  /**
   * Confirms successful cancellation of a workflow activity to the server using workflow and
   * activity IDs.
   *
   * <p>This overload is only for workflow activities. To cancel a standalone activity by ID, use
   * {@link #reportCancellationStandalone(String, Optional, Object)}.
   *
   * @param workflowId id of the workflow that started the activity
   * @param runId optional run id of the workflow that started the activity
   * @param activityId id of the activity
   * @param details details to record with the cancellation
   */
  <V> void reportCancellation(
      String workflowId, Optional<String> runId, String activityId, V details)
      throws ActivityCompletionException;

  /**
   * Confirms successful cancellation of a standalone activity to the server using activity ID.
   *
   * <p>This method is only for standalone activities. To cancel a workflow activity by ID, use
   * {@link #reportCancellation(String, Optional, String, Object)}.
   *
   * @param activityId id of the standalone activity
   * @param activityRunId optional run id of the standalone activity, or {@code Optional.empty()}
   * @param details details to record with the cancellation
   */
  <V> void reportCancellationStandalone(
      String activityId, Optional<String> activityRunId, V details)
      throws ActivityCompletionException;

  /**
   * Records a heartbeat for an activity using a task token.
   *
   * <p>This overload works with both workflow activities and standalone activities.
   *
   * @param taskToken token of the activity attempt
   * @param details details to record with the heartbeat
   * @throws ActivityCompletionException if activity should stop executing
   */
  <V> void heartbeat(byte[] taskToken, V details) throws ActivityCompletionException;

  /**
   * Records a heartbeat for a workflow activity using workflow and activity IDs.
   *
   * <p>This overload is only for workflow activities. To heartbeat a standalone activity by ID, use
   * {@link #heartbeatStandalone(String, Optional, Object)}.
   *
   * @param workflowId id of the workflow that started the activity
   * @param runId optional run id of the workflow that started the activity
   * @param activityId id of the activity
   * @param details details to record with the heartbeat
   * @throws ActivityCompletionException if activity should stop executing
   */
  <V> void heartbeat(String workflowId, Optional<String> runId, String activityId, V details)
      throws ActivityCompletionException;

  /**
   * Records a heartbeat for a standalone activity using activity ID.
   *
   * <p>This method is only for standalone activities. To heartbeat a workflow activity by ID, use
   * {@link #heartbeat(String, Optional, String, Object)}.
   *
   * @param activityId id of the standalone activity
   * @param activityRunId optional run id of the standalone activity, or {@code Optional.empty()}
   * @param details details to record with the heartbeat
   * @throws ActivityCompletionException if activity should stop executing
   */
  <V> void heartbeatStandalone(String activityId, Optional<String> activityRunId, V details)
      throws ActivityCompletionException;

  /**
   * Supply this context if correct serialization of activity heartbeats, results or other payloads
   * requires {@link DataConverter}, {@link PayloadConverter} or {@link PayloadCodec} to be aware of
   * {@link ActivitySerializationContext}.
   *
   * @param context provides information to the data converter about the abstraction the data
   *     belongs to
   * @return an instance of DataConverter that may use the provided {@code context} for
   *     serialization
   * @see SerializationContext
   */
  @Experimental
  @Nonnull
  ActivityCompletionClient withContext(@Nonnull ActivitySerializationContext context);
}
