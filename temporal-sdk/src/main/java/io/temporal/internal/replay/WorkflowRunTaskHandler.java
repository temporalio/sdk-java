package io.temporal.internal.replay;

import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder;
import io.temporal.worker.NonDeterministicException;

/**
 * Task handler that encapsulates a cached workflow and can handle multiple calls to
 * handleWorkflowTask for the same workflow run.
 *
 * <p>Instances of this object can be cached in between workflow tasks.
 */
public interface WorkflowRunTaskHandler {

  /**
   * Handles a single new workflow task of the workflow.
   *
   * @param workflowTask task to handle
   * @return an object that can be used to build workflow task completion or failure response
   * @throws Throwable if processing experienced issues that are considered unrecoverable inside the
   *     current workflow task. {@link NonDeterministicException} or {@link Error} are such cases.
   */
  WorkflowTaskResult handleWorkflowTask(
      PollWorkflowTaskQueueResponseOrBuilder workflowTask, WorkflowHistoryIterator historyIterator)
      throws Throwable;

  /**
   * Handles a Direct Query (or Legacy Query) scenario. In this case, it's not a real workflow task
   * and the processing can't generate any new commands.
   *
   * @param workflowTask task to handle
   * @return an object that can be used to build a legacy query response
   * @throws Throwable if processing experienced issues that are considered unrecoverable inside the
   *     current workflow task. {@link NonDeterministicException} or {@link Error} are such cases.
   */
  QueryResult handleDirectQueryWorkflowTask(
      PollWorkflowTaskQueueResponseOrBuilder workflowTask, WorkflowHistoryIterator historyIterator)
      throws Throwable;

  /**
   * Reset the workflow event ID.
   *
   * @param eventId the event ID to reset the cached state to.
   */
  void resetStartedEventId(Long eventId);

  void close();
}
