package io.temporal.common.interceptors;

import io.temporal.client.ActivityFailedException;
import io.temporal.client.WorkflowUpdateHandle;
import java.util.concurrent.TimeoutException;

/** Convenience base class for {@link WorkflowClientCallsInterceptor} implementations. */
public class WorkflowClientCallsInterceptorBase implements WorkflowClientCallsInterceptor {

  private final WorkflowClientCallsInterceptor next;

  public WorkflowClientCallsInterceptorBase(WorkflowClientCallsInterceptor next) {
    this.next = next;
  }

  @Override
  public WorkflowStartOutput start(WorkflowStartInput input) {
    return next.start(input);
  }

  @Override
  public WorkflowSignalOutput signal(WorkflowSignalInput input) {
    return next.signal(input);
  }

  @Override
  public WorkflowSignalWithStartOutput signalWithStart(WorkflowSignalWithStartInput input) {
    return next.signalWithStart(input);
  }

  @Override
  public <R> WorkflowUpdateWithStartOutput<R> updateWithStart(
      WorkflowUpdateWithStartInput<R> input) {
    return next.updateWithStart(input);
  }

  @Override
  public <R> GetResultOutput<R> getResult(GetResultInput<R> input) throws TimeoutException {
    return next.getResult(input);
  }

  @Override
  public <R> GetResultAsyncOutput<R> getResultAsync(GetResultInput<R> input) {
    return next.getResultAsync(input);
  }

  @Override
  public <R> QueryOutput<R> query(QueryInput<R> input) {
    return next.query(input);
  }

  @Override
  public <R> WorkflowUpdateHandle<R> startUpdate(StartUpdateInput<R> input) {
    return next.startUpdate(input);
  }

  @Override
  public <R> PollWorkflowUpdateOutput<R> pollWorkflowUpdate(PollWorkflowUpdateInput<R> input) {
    return next.pollWorkflowUpdate(input);
  }

  @Override
  public CancelOutput cancel(CancelInput input) {
    return next.cancel(input);
  }

  @Override
  public TerminateOutput terminate(TerminateInput input) {
    return next.terminate(input);
  }

  @Override
  public DescribeWorkflowOutput describe(DescribeWorkflowInput input) {
    return next.describe(input);
  }

  @Override
  public ListWorkflowExecutionsOutput listWorkflowExecutions(ListWorkflowExecutionsInput input) {
    return next.listWorkflowExecutions(input);
  }

  @Override
  public CountWorkflowOutput countWorkflows(CountWorkflowsInput input) {
    return next.countWorkflows(input);
  }

  @Override
  public StartActivityOutput startActivity(StartActivityInput input) {
    return next.startActivity(input);
  }

  @Override
  public <R> GetActivityResultOutput<R> getActivityResult(GetActivityResultInput<R> input)
      throws ActivityFailedException {
    return next.getActivityResult(input);
  }

  @Override
  public DescribeActivityOutput describeActivity(DescribeActivityInput input) {
    return next.describeActivity(input);
  }

  @Override
  public CancelActivityOutput cancelActivity(CancelActivityInput input) {
    return next.cancelActivity(input);
  }

  @Override
  public TerminateActivityOutput terminateActivity(TerminateActivityInput input) {
    return next.terminateActivity(input);
  }

  @Override
  public ListActivitiesOutput listActivities(ListActivitiesInput input) {
    return next.listActivities(input);
  }

  @Override
  public CountActivitiesOutput countActivities(CountActivitiesInput input) {
    return next.countActivities(input);
  }

  @Override
  public ListActivitiesPaginatedOutput listActivitiesPaginated(ListActivitiesPaginatedInput input) {
    return next.listActivitiesPaginated(input);
  }
}
