package io.temporal.internal.worker;

import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributesOrBuilder;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.worker.tuning.SlotPermit;
import java.util.Optional;

public interface EagerActivityDispatcher {
  Optional<SlotPermit> tryReserveActivitySlot(
      ScheduleActivityTaskCommandAttributesOrBuilder commandAttributes);

  void releaseActivitySlotReservations(Iterable<SlotPermit> permits);

  void dispatchActivity(PollActivityTaskQueueResponse activity, SlotPermit permit);

  class NoopEagerActivityDispatcher implements EagerActivityDispatcher {
    @Override
    public Optional<SlotPermit> tryReserveActivitySlot(
        ScheduleActivityTaskCommandAttributesOrBuilder commandAttributes) {
      return Optional.empty();
    }

    @Override
    public void releaseActivitySlotReservations(Iterable<SlotPermit> permits) {
      if (permits.iterator().hasNext())
        throw new IllegalStateException(
            "Trying to release activity slots on a NoopEagerActivityDispatcher");
    }

    @Override
    public void dispatchActivity(PollActivityTaskQueueResponse activity, SlotPermit permit) {
      throw new IllegalStateException(
          "Trying to dispatch activity on a NoopEagerActivityDispatcher");
    }
  }
}
