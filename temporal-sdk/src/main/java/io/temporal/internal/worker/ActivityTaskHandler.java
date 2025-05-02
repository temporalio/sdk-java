package io.temporal.internal.worker;

import com.uber.m3.tally.Scope;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest;

/**
 * Interface of an activity task handler.
 *
 * @author fateev
 */
public interface ActivityTaskHandler {

  final class Result {

    private final String activityId;
    private final RespondActivityTaskCompletedRequest taskCompleted;
    private final TaskFailedResult taskFailed;
    private final RespondActivityTaskCanceledRequest taskCanceled;
    private final boolean manualCompletion;

    @Override
    public String toString() {
      return "Result{"
          + "activityId='"
          + activityId
          + '\''
          + ", taskCompleted="
          + taskCompleted
          + ", taskFailed="
          + taskFailed
          + ", taskCanceled="
          + taskCanceled
          + '}';
    }

    public static class TaskFailedResult {
      private final RespondActivityTaskFailedRequest taskFailedRequest;
      private final Throwable failure;

      public TaskFailedResult(
          RespondActivityTaskFailedRequest taskFailedRequest, Throwable failure) {
        this.taskFailedRequest = taskFailedRequest;
        this.failure = failure;
      }

      public RespondActivityTaskFailedRequest getTaskFailedRequest() {
        return taskFailedRequest;
      }

      public Throwable getFailure() {
        return failure;
      }
    }

    /**
     * Only zero (manual activity completion) or one request is allowed. Task token and identity
     * fields shouldn't be filled in. Retry options are the service call. These options override the
     * default ones set on the activity worker.
     */
    public Result(
        String activityId,
        RespondActivityTaskCompletedRequest taskCompleted,
        TaskFailedResult taskFailed,
        RespondActivityTaskCanceledRequest taskCanceled,
        boolean manualCompletion) {
      this.activityId = activityId;
      this.taskCompleted = taskCompleted;
      this.taskFailed = taskFailed;
      this.taskCanceled = taskCanceled;
      this.manualCompletion = manualCompletion;
    }

    public String getActivityId() {
      return activityId;
    }

    public RespondActivityTaskCompletedRequest getTaskCompleted() {
      return taskCompleted;
    }

    public TaskFailedResult getTaskFailed() {
      return taskFailed;
    }

    public RespondActivityTaskCanceledRequest getTaskCanceled() {
      return taskCanceled;
    }

    public boolean isManualCompletion() {
      return manualCompletion;
    }
  }

  /**
   * The implementation should be called when a polling activity worker receives a new activity
   * task. This method shouldn't throw any Throwables unless there is a need to not reply to the
   * task.
   *
   * @param activityTask activity task which is response to PollActivityTaskQueue call.
   * @return One of the possible activity task replies.
   */
  Result handle(ActivityTask activityTask, Scope metricsScope, boolean isLocalActivity);

  /** True if this handler handles at least one activity type. */
  boolean isAnyTypeSupported();

  /**
   * @param activityType activity type name
   * @return true if an activity implementation with {@code activityType} name is registered or a
   *     dynamic activity implementation is registered.
   */
  boolean isTypeSupported(String activityType);
}
