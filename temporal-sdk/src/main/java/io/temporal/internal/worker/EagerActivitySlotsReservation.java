package io.temporal.internal.worker;

import com.google.common.base.Preconditions;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse;
import io.temporal.internal.Config;
import io.temporal.worker.tuning.SlotPermit;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.concurrent.NotThreadSafe;

/** This class is not thread safe and shouldn't leave the boundaries of one activity executor */
@NotThreadSafe
class EagerActivitySlotsReservation implements Closeable {
  private final EagerActivityDispatcher eagerActivityDispatcher;
  private final List<SlotPermit> reservedSlots = new ArrayList<>();

  EagerActivitySlotsReservation(EagerActivityDispatcher eagerActivityDispatcher) {
    this.eagerActivityDispatcher = eagerActivityDispatcher;
  }

  public void applyToRequest(RespondWorkflowTaskCompletedRequest.Builder mutableRequest) {
    for (int i = 0; i < mutableRequest.getCommandsCount(); i++) {
      Command command = mutableRequest.getCommands(i);
      if (command.getCommandType() != CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK) continue;

      ScheduleActivityTaskCommandAttributes commandAttributes =
          command.getScheduleActivityTaskCommandAttributes();
      if (!commandAttributes.getRequestEagerExecution()) continue;
      boolean atLimit = this.reservedSlots.size() >= Config.EAGER_ACTIVITIES_LIMIT;
      Optional<SlotPermit> permit = Optional.empty();
      if (!atLimit) {
        permit = this.eagerActivityDispatcher.tryReserveActivitySlot(commandAttributes);
      }

      if (permit.isPresent()) {
        this.reservedSlots.add(permit.get());
      } else {
        mutableRequest.setCommands(
            i,
            command.toBuilder()
                .setScheduleActivityTaskCommandAttributes(
                    commandAttributes.toBuilder().setRequestEagerExecution(false)));
      }
    }
  }

  public void handleResponse(RespondWorkflowTaskCompletedResponse serverResponse) {
    int activityTasksCount = serverResponse.getActivityTasksCount();
    Preconditions.checkArgument(
        activityTasksCount <= this.reservedSlots.size(),
        "Unexpectedly received %s eager activities though we only requested %s",
        activityTasksCount,
        this.reservedSlots.size());

    for (PollActivityTaskQueueResponse act : serverResponse.getActivityTasksList()) {
      // don't release slots here, instead the release function is called in the activity worker to
      // release when the activity is done
      SlotPermit permit = this.reservedSlots.remove(0);
      this.eagerActivityDispatcher.dispatchActivity(act, permit);
    }

    // Release any remaining that we won't be using
    try {
      this.eagerActivityDispatcher.releaseActivitySlotReservations(this.reservedSlots);
    } finally {
      this.reservedSlots.clear();
    }
  }

  @Override
  public void close() {
    if (!this.reservedSlots.isEmpty()) {
      // Release all slots
      this.eagerActivityDispatcher.releaseActivitySlotReservations(this.reservedSlots);
      this.reservedSlots.clear();
    }
  }
}
