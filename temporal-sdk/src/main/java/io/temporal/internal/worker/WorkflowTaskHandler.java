package io.temporal.internal.worker;

import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.workflow.Functions;

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

    public Result(
        String workflowType,
        RespondWorkflowTaskCompletedRequest taskCompleted,
        RespondWorkflowTaskFailedRequest taskFailed,
        RespondQueryTaskCompletedRequest queryCompleted,
        RpcRetryOptions requestRetryOptions,
        boolean completionCommand,
        Functions.Proc1<Long> resetEventIdHandle,
        Runnable applyPostCompletionMetrics) {
      this.workflowType = workflowType;
      this.taskCompleted = taskCompleted;
      this.taskFailed = taskFailed;
      this.queryCompleted = queryCompleted;
      this.requestRetryOptions = requestRetryOptions;
      this.completionCommand = completionCommand;
      this.resetEventIdHandle = resetEventIdHandle;
      this.applyPostCompletionMetrics = applyPostCompletionMetrics;
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
