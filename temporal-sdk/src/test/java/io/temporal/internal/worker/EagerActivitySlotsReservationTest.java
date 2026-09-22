package io.temporal.internal.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import io.temporal.worker.tuning.SlotPermit;
import java.util.Optional;
import org.junit.Test;

public class EagerActivitySlotsReservationTest {
  @Test
  public void limitsReservationsPerWorkflowTask() {
    EagerActivityDispatcher dispatcher = mock(EagerActivityDispatcher.class);
    when(dispatcher.tryReserveActivitySlot(any())).thenReturn(Optional.of(mock(SlotPermit.class)));
    RespondWorkflowTaskCompletedRequest.Builder request =
        RespondWorkflowTaskCompletedRequest.newBuilder();
    for (int i = 0; i < 5; i++) {
      request.addCommands(
          Command.newBuilder()
              .setCommandType(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK)
              .setScheduleActivityTaskCommandAttributes(
                  ScheduleActivityTaskCommandAttributes.newBuilder()
                      .setRequestEagerExecution(true)));
    }

    try (EagerActivitySlotsReservation reservation =
        new EagerActivitySlotsReservation(dispatcher, 2)) {
      reservation.applyToRequest(request);
      assertEquals(5, request.getCommandsCount());
      assertTrue(
          request
              .getCommands(0)
              .getScheduleActivityTaskCommandAttributes()
              .getRequestEagerExecution());
      assertTrue(
          request
              .getCommands(1)
              .getScheduleActivityTaskCommandAttributes()
              .getRequestEagerExecution());
      for (int i = 2; i < 5; i++) {
        assertFalse(
            request
                .getCommands(i)
                .getScheduleActivityTaskCommandAttributes()
                .getRequestEagerExecution());
      }
    }
    verify(dispatcher, times(2)).tryReserveActivitySlot(any());
  }
}
