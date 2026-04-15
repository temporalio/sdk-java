package io.temporal.common.interceptors;

import io.temporal.client.ActivityAlreadyStartedException;
import io.temporal.client.ActivityFailedException;

/** Convenience base class for {@link ActivityClientCallsInterceptor} implementations. */
public class ActivityClientCallsInterceptorBase implements ActivityClientCallsInterceptor {

  private final ActivityClientCallsInterceptor next;

  public ActivityClientCallsInterceptorBase(ActivityClientCallsInterceptor next) {
    this.next = next;
  }

  @Override
  public StartActivityOutput startActivity(StartActivityInput input)
      throws ActivityAlreadyStartedException {
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
  public ListActivitiesPaginatedOutput listActivitiesPaginated(ListActivitiesPaginatedInput input) {
    return next.listActivitiesPaginated(input);
  }

  @Override
  public CountActivitiesOutput countActivities(CountActivitiesInput input) {
    return next.countActivities(input);
  }
}
