package io.temporal.internal.worker;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.workflow.Functions;
import javax.annotation.Nullable;

/**
 * Interface of workflow task handlers.
 *
 * @author fateev, suskin
 */
public interface WorkflowTaskHandler {

  final class Result {
    private final String workflowType;
    private final RespondWorkflowTaskCompletedRequest taskCompleted;
    private final RespondWorkflowTaskFailedRequest taskFailed;
    private final RespondQueryTaskCompletedRequest queryCompleted;
    private final RpcRetryOptions requestRetryOptions;
    private final boolean completionCommand;
    private final Functions.Proc1<Long> resetEventIdHandle;
    private final Runnable applyPostCompletionMetrics;
    private final @Nullable WorkflowExecution completionParentExecution;

    public Result(
        String workflowType,
        RespondWorkflowTaskCompletedRequest taskCompleted,
        RespondWorkflowTaskFailedRequest taskFailed,
        RespondQueryTaskCompletedRequest queryCompleted,
        RpcRetryOptions requestRetryOptions,
        boolean completionCommand,
        Functions.Proc1<Long> resetEventIdHandle,
        Runnable applyPostCompletionMetrics) {
      this(
          workflowType,
          taskCompleted,
          taskFailed,
          queryCompleted,
          requestRetryOptions,
          completionCommand,
          resetEventIdHandle,
          applyPostCompletionMetrics,
          null);
    }

    public Result(
        String workflowType,
        RespondWorkflowTaskCompletedRequest taskCompleted,
        RespondWorkflowTaskFailedRequest taskFailed,
        RespondQueryTaskCompletedRequest queryCompleted,
        RpcRetryOptions requestRetryOptions,
        boolean completionCommand,
        Functions.Proc1<Long> resetEventIdHandle,
        Runnable applyPostCompletionMetrics,
        @Nullable WorkflowExecution completionParentExecution) {
      this.completionParentExecution = completionParentExecution;
      this.workflowType = workflowType;
      this.taskCompleted = taskCompleted;
      this.taskFailed = taskFailed;
      this.queryCompleted = queryCompleted;
      this.requestRetryOptions = requestRetryOptions;
      this.completionCommand = completionCommand;
      this.resetEventIdHandle = resetEventIdHandle;
      this.applyPostCompletionMetrics = applyPostCompletionMetrics;
    }

    /**
     * The workflow to attribute this workflow's own result to, or {@code null} to attribute it to
     * the workflow itself.
     */
    @Nullable
    public WorkflowExecution getCompletionParentExecution() {
      return completionParentExecution;
    }

    public RespondWorkflowTaskCompletedRequest getTaskCompleted() {
      return taskCompleted;
    }

    public RespondWorkflowTaskFailedRequest getTaskFailed() {
      return taskFailed;
    }

    public RespondQueryTaskCompletedRequest getQueryCompleted() {
      return queryCompleted;
    }

    public RpcRetryOptions getRequestRetryOptions() {
      return requestRetryOptions;
    }

    public boolean isCompletionCommand() {
      return completionCommand;
    }

    public Functions.Proc1<Long> getResetEventIdHandle() {
      if (resetEventIdHandle != null) {
        return resetEventIdHandle;
      }
      return (arg) -> {};
    }

    public Runnable getApplyPostCompletionMetrics() {
      return applyPostCompletionMetrics;
    }

    @Override
    public String toString() {
      return "Result{"
          + "workflowType='"
          + workflowType
          + '\''
          + ", taskCompleted="
          + taskCompleted
          + ", taskFailed="
          + taskFailed
          + ", queryCompleted="
          + queryCompleted
          + ", requestRetryOptions="
          + requestRetryOptions
          + ", completionCommand="
          + completionCommand
          + '}';
    }

    public String getWorkflowType() {
      return workflowType;
    }
  }

  /**
   * Handles a single workflow task
   *
   * @param workflowTask The workflow task to handle.
   * @return One of the possible workflow task replies: RespondWorkflowTaskCompletedRequest,
   *     RespondQueryTaskCompletedRequest, RespondWorkflowTaskFailedRequest
   * @throws Exception an original exception or error if the processing should be just abandoned
   *     without replying to the server
   */
  Result handleWorkflowTask(PollWorkflowTaskQueueResponse workflowTask) throws Exception;

  /** True if this handler handles at least one workflow type. */
  boolean isAnyTypeSupported();
}
