package io.temporal.common.interceptors;

import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.client.schedules.*;
import io.temporal.common.Experimental;
import io.temporal.workflow.Functions;
import java.util.List;
import java.util.stream.Stream;

/**
 * Intercepts calls to the {@link io.temporal.client.schedules.ScheduleClient} and {@link
 * io.temporal.client.schedules.ScheduleHandle} related to the lifecycle of a Schedule.
 *
 * <p>Prefer extending {@link ScheduleClientCallsInterceptorBase} and override only the methods you
 * need instead of implementing this interface directly. {@link ScheduleClientCallsInterceptor}
 * provides correct default implementations to all the methods of this interface.
 */
@Experimental
public interface ScheduleClientCallsInterceptor {

  void createSchedule(CreateScheduleInput input);

  ListScheduleOutput listSchedules(ListSchedulesInput input);

  void backfillSchedule(BackfillScheduleInput input);

  void deleteSchedule(DeleteScheduleInput input);

  DescribeScheduleOutput describeSchedule(DescribeScheduleInput input);

  void pauseSchedule(PauseScheduleInput input);

  void triggerSchedule(TriggerScheduleInput input);

  void unpauseSchedule(UnpauseScheduleInput input);

  void updateSchedule(UpdateScheduleInput input);

  class CreateScheduleInput {
    private final String Id;
    private final Schedule schedule;
    private final ScheduleOptions options;

    public CreateScheduleInput(String id, Schedule schedule, ScheduleOptions options) {
      Id = id;
      this.schedule = schedule;
      this.options = options;
    }

    public String getId() {
      return Id;
    }

    public Schedule getSchedule() {
      return schedule;
    }

    public ScheduleOptions getOptions() {
      return options;
    }
  }

  class ListSchedulesInput {
    private final String query;
    private final int pageSize;

    public ListSchedulesInput(String query, int pageSize) {
      this.query = query;
      this.pageSize = pageSize;
    }

    public int getPageSize() {
      return pageSize;
    }

    public String getQuery() {
      return query;
    }
  }

  class ListScheduleOutput {
    public Stream<ScheduleListDescription> getStream() {
      return stream;
    }

    private final Stream<ScheduleListDescription> stream;

    public ListScheduleOutput(Stream<ScheduleListDescription> stream) {
      this.stream = stream;
    }
  }

  class BackfillScheduleInput {
    private final String scheduleId;

    public String getScheduleId() {
      return scheduleId;
    }

    public List<ScheduleBackfill> getBackfills() {
      return backfills;
    }

    private final List<ScheduleBackfill> backfills;

    public BackfillScheduleInput(String scheduleId, List<ScheduleBackfill> backfills) {
      this.scheduleId = scheduleId;
      this.backfills = backfills;
    }
  }

  class DeleteScheduleInput {
    public String getScheduleId() {
      return scheduleId;
    }

    private final String scheduleId;

    public DeleteScheduleInput(String scheduleId) {
      this.scheduleId = scheduleId;
    }
  }

  class DescribeScheduleInput {
    public String getScheduleId() {
      return scheduleId;
    }

    private final String scheduleId;

    public DescribeScheduleInput(String scheduleId) {
      this.scheduleId = scheduleId;
    }
  }

  class DescribeScheduleOutput {
    private final ScheduleDescription description;

    public DescribeScheduleOutput(ScheduleDescription description) {
      this.description = description;
    }

    public ScheduleDescription getDescription() {
      return description;
    }
  }

  class PauseScheduleInput {
    public String getScheduleId() {
      return scheduleId;
    }

    public String getNote() {
      return note;
    }

    private final String scheduleId;
    private final String note;

    public PauseScheduleInput(String scheduleId, String note) {
      this.scheduleId = scheduleId;
      this.note = note;
    }
  }

  class TriggerScheduleInput {
    private final String scheduleId;
    private final ScheduleOverlapPolicy overlapPolicy;

    public String getScheduleId() {
      return scheduleId;
    }

    public ScheduleOverlapPolicy getOverlapPolicy() {
      return overlapPolicy;
    }

    public TriggerScheduleInput(String scheduleId, ScheduleOverlapPolicy overlapPolicy) {
      this.scheduleId = scheduleId;
      this.overlapPolicy = overlapPolicy;
    }
  }

  class UnpauseScheduleInput {
    public String getScheduleId() {
      return scheduleId;
    }

    public String getNote() {
      return note;
    }

    private final String scheduleId;
    private final String note;

    public UnpauseScheduleInput(String scheduleId, String note) {
      this.scheduleId = scheduleId;
      this.note = note;
    }
  }

  class UpdateScheduleInput {
    public ScheduleDescription getDescription() {
      return description;
    }

    public Functions.Func1<ScheduleUpdateInput, ScheduleUpdate> getUpdater() {
      return updater;
    }

    private final ScheduleDescription description;
    private final Functions.Func1<ScheduleUpdateInput, ScheduleUpdate> updater;

    public UpdateScheduleInput(
        ScheduleDescription description,
        Functions.Func1<ScheduleUpdateInput, ScheduleUpdate> updater) {
      this.description = description;
      this.updater = updater;
    }
  }
}
