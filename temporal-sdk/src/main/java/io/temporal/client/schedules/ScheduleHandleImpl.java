package io.temporal.client.schedules;

import com.google.common.base.Preconditions;
import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.common.interceptors.ScheduleClientCallsInterceptor;
import io.temporal.workflow.Functions;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

class ScheduleHandleImpl implements ScheduleHandle {
  private final String Id;
  private final ScheduleClientCallsInterceptor interceptor;

  public ScheduleHandleImpl(ScheduleClientCallsInterceptor interceptor, String Id) {
    this.interceptor = interceptor;
    this.Id = Id;
  }

  @Override
  public String getId() {
    return this.Id;
  }

  @Override
  public void backfill(List<ScheduleBackfill> backfills) {
    Preconditions.checkState(backfills.size() > 0, "At least one backfill required");
    interceptor.backfillSchedule(
        new ScheduleClientCallsInterceptor.BackfillScheduleInput(Id, backfills));
  }

  @Override
  public void delete() {
    interceptor.deleteSchedule(new ScheduleClientCallsInterceptor.DeleteScheduleInput(Id));
  }

  @Override
  public ScheduleDescription describe() {
    return interceptor
        .describeSchedule(new ScheduleClientCallsInterceptor.DescribeScheduleInput(Id))
        .getDescription();
  }

  @Override
  public void pause(@Nonnull String note) {
    Objects.requireNonNull(note);
    interceptor.pauseSchedule(
        new ScheduleClientCallsInterceptor.PauseScheduleInput(
            Id, note.isEmpty() ? "Paused via Java SDK" : note));
  }

  @Override
  public void pause() {
    pause("");
  }

  @Override
  public void trigger(ScheduleOverlapPolicy overlapPolicy) {
    interceptor.triggerSchedule(
        new ScheduleClientCallsInterceptor.TriggerScheduleInput(Id, overlapPolicy));
  }

  @Override
  public void trigger() {
    interceptor.triggerSchedule(
        new ScheduleClientCallsInterceptor.TriggerScheduleInput(
            Id, ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_UNSPECIFIED));
  }

  @Override
  public void unpause(@Nonnull String note) {
    Objects.requireNonNull(note);
    interceptor.unpauseSchedule(
        new ScheduleClientCallsInterceptor.UnpauseScheduleInput(
            Id, note.isEmpty() ? "Unpaused via Java SDK" : note));
  }

  @Override
  public void unpause() {
    unpause("");
  }

  @Override
  public void update(Functions.Func1<ScheduleUpdateInput, ScheduleUpdate> updater) {
    interceptor.updateSchedule(
        new ScheduleClientCallsInterceptor.UpdateScheduleInput(this.describe(), updater));
  }
}
