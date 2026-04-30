package io.temporal.common.interceptors;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/** Convenience base class for {@link ActivityClientCallsInterceptor} implementations. */
public class ActivityClientCallsInterceptorBase implements ActivityClientCallsInterceptor {

  private final ActivityClientCallsInterceptor next;

  public ActivityClientCallsInterceptorBase(ActivityClientCallsInterceptor next) {
    this.next = next;
  }

  @Override
  public StartActivityOutput startActivity(StartActivityInput input) {
    return next.startActivity(input);
  }

  @Override
  public <R> GetActivityResultOutput<R> getActivityResult(GetActivityResultInput<R> input)
      throws TimeoutException {
    return next.getActivityResult(input);
  }

  @Override
  public <R> CompletableFuture<GetActivityResultOutput<R>> getActivityResultAsync(
      GetActivityResultInput<R> input) {
    return next.getActivityResultAsync(input);
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
}
