package io.temporal.internal.statemachines;

import static io.temporal.internal.statemachines.NexusOperationStateMachineTest.*;
import static io.temporal.internal.statemachines.TestHistoryBuilder.assertCommand;
import static org.junit.Assert.*;

import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.RequestCancelNexusOperationCommandAttributes;
import io.temporal.api.command.v1.ScheduleNexusOperationCommandAttributes;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.AfterClass;
import org.junit.Test;

public class CancelNexusOperationStateMachineTest {
  private WorkflowStateMachines stateMachines;

  private static final List<
          StateMachine<
              CancelNexusOperationStateMachine.State,
              CancelNexusOperationStateMachine.ExplicitEvent,
              CancelNexusOperationStateMachine>>
      stateMachineList = new ArrayList<>();

  private WorkflowStateMachines newStateMachines(TestEntityManagerListenerBase listener) {
    return new WorkflowStateMachines(listener, (stateMachineList::add));
  }

  @AfterClass
  public static void generateCoverage() {
    List<
            Transition<
                CancelNexusOperationStateMachine.State,
                TransitionEvent<CancelNexusOperationStateMachine.ExplicitEvent>>>
        missed =
            CancelNexusOperationStateMachine.STATE_MACHINE_DEFINITION.getUnvisitedTransitions(
                stateMachineList);
    if (!missed.isEmpty()) {
      CommandsGeneratePlantUMLStateDiagrams.writeToFile(
          "test",
          CancelNexusOperationStateMachine.class,
          CancelNexusOperationStateMachine.STATE_MACHINE_DEFINITION.asPlantUMLStateDiagramCoverage(
              stateMachineList));
      fail(
          "CancelNexusOperationStateMachine is missing test coverage for the following transitions:\n"
              + missed);
    }
  }

  @Test
  public void testCancelNexusOperationStateMachine() {
    class TestListener extends TestEntityManagerListenerBase {
      @Override
      protected void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        RequestCancelNexusOperationCommandAttributes cancelAttributes =
            RequestCancelNexusOperationCommandAttributes.newBuilder()
                .setScheduledEventId(5)
                .build();
        ScheduleNexusOperationCommandAttributes scheduleAttributes =
            newScheduleNexusOperationCommandAttributesBuilder().build();
        NexusOperationStateMachineTest.DelayedCallback2<Optional<Payload>, Failure>
            delayedCallback = new NexusOperationStateMachineTest.DelayedCallback2();
        builder
            .<Optional<String>, Failure>add2(
                (v, c) ->
                    stateMachines.startNexusOperation(
                        scheduleAttributes, null, c, delayedCallback::run))
            .add((v) -> stateMachines.requestCancelNexusOperation(cancelAttributes))
            .<Optional<Payload>, Failure>add2((pair, c) -> delayedCallback.set(c))
            .add((pair) -> stateMachines.failWorkflow(pair.getT2()));
      }
    }
    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_NEXUS_OPERATION_SCHEDULED
        6: EVENT_TYPE_NEXUS_OPERATION_STARTED
        7: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        8: EVENT_TYPE_WORKFLOW_TASK_STARTED
        9: EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUESTED
        10: EVENT_TYPE_NEXUS_OPERATION_CANCELED
        11: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        12: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask();
    long scheduledEventId =
        h.addGetEventId(
            EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED,
            newNexusOperationScheduledEventAttributesBuilder().build());
    h.add(
            EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED,
            NexusOperationStartedEventAttributes.newBuilder()
                .setScheduledEventId(scheduledEventId)
                .setRequestId("requestId")
                .setOperationId(OPERATION_ID)
                .build())
        .addWorkflowTask()
        .add(
            EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUESTED,
            NexusOperationCancelRequestedEventAttributes.newBuilder()
                .setScheduledEventId(scheduledEventId)
                .build())
        .add(
            EventType.EVENT_TYPE_NEXUS_OPERATION_CANCELED,
            NexusOperationCanceledEventAttributes.newBuilder()
                .setScheduledEventId(scheduledEventId)
                .setFailure(Failure.newBuilder().setMessage("canceled").build())
                .build())
        .addWorkflowTaskScheduledAndStarted();
    {
      TestEntityManagerListenerBase listener = new TestListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_SCHEDULE_NEXUS_OPERATION, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertEquals(1, commands.size());
      assertCommand(CommandType.COMMAND_TYPE_REQUEST_CANCEL_NEXUS_OPERATION, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 3);
      assertEquals(1, commands.size());
      assertCommand(CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION, commands);
    }
  }
}
