package io.temporal.common.interceptors;

/** Convenience base class for {@link ScheduleClientCallsInterceptor} implementations. */
public class ScheduleClientCallsInterceptorBase implements ScheduleClientCallsInterceptor {

  private final ScheduleClientCallsInterceptor next;

  public ScheduleClientCallsInterceptorBase(ScheduleClientCallsInterceptor next) {
    this.next = next;
  }

  @Override
  public void createSchedule(CreateScheduleInput input) {
    next.createSchedule(input);
  }

  @Override
  public ListScheduleOutput listSchedules(ListSchedulesInput input) {
    return next.listSchedules(input);
  }

  @Override
  public void backfillSchedule(BackfillScheduleInput input) {
    next.backfillSchedule(input);
  }

  @Override
  public void deleteSchedule(DeleteScheduleInput input) {
    next.deleteSchedule(input);
  }

  @Override
  public DescribeScheduleOutput describeSchedule(DescribeScheduleInput input) {
    return next.describeSchedule(input);
  }

  @Override
  public void pauseSchedule(PauseScheduleInput input) {
    next.pauseSchedule(input);
  }

  @Override
  public void triggerSchedule(TriggerScheduleInput input) {
    next.triggerSchedule(input);
  }

  @Override
  public void unpauseSchedule(UnpauseScheduleInput input) {
    next.unpauseSchedule(input);
  }

  @Override
  public void updateSchedule(UpdateScheduleInput input) {
    next.updateSchedule(input);
  }
}
