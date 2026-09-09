package io.temporal.testserver.functional;

import static io.temporal.internal.common.InternalUtils.createNormalTaskQueue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.temporal.api.command.v1.CancelTimerCommandAttributes;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.CompleteWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.command.v1.StartTimerCommandAttributes;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.TestServiceUtils;
import io.temporal.testserver.TestServer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies that a timer that fired while a workflow task was in progress can be cancelled by that
 * same workflow task. The buffered TIMER_FIRED event is replaced with a TIMER_CANCELED event
 * instead of failing the workflow task completion with INVALID_ARGUMENT.
 *
 * @see <a href="https://github.com/temporalio/sdk-java/issues/2606">Issue 2606</a>
 */
public class CancelFiredTimerTest {

  private static final Duration TIMER_DURATION = Duration.ofSeconds(2);
  private static final long TIMER_FIRING_WAIT_MILLIS = 4000;

  private static final String NAMESPACE = "namespace";
  private static final String TASK_QUEUE = "taskQueue";
  private static final String WORKFLOW_TYPE = "wfType";
  private static final String TIMER_ID = "timer";
  private static final String ACTIVITY_ID = "activity";

  private TestServer.InProcessTestServer testServer;
  private WorkflowServiceStubs workflowServiceStubs;

  @Before
  public void setUp() {
    this.testServer = TestServer.createServer(true);
    this.workflowServiceStubs =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setChannel(testServer.getChannel())
                .validateAndBuildWithDefaults());
  }

  @After
  public void tearDown() {
    this.workflowServiceStubs.shutdownNow();
    this.workflowServiceStubs.awaitTermination(1, TimeUnit.SECONDS);
    this.testServer.close();
  }

  /**
   * Verifies that the workflow task that cancels the fired timer can also complete the workflow in
   * the same response. Previously the completion failed with INVALID_ARGUMENT "UnhandledCommand".
   */
  @Test
  public void cancelFiredTimerWithWorkflowCompletion() throws Exception {
    String workflowId = UUID.randomUUID().toString();
    PollWorkflowTaskQueueResponse task = startWorkflowAndPollTaskWithFiredTimer(workflowId);

    respondWorkflowTaskCompleted(
        task.getTaskToken(), cancelTimerCommand(), completeWorkflowCommand());

    List<HistoryEvent> history = getHistory(workflowId);
    assertCompleted(history);
    assertTimerCancelledAndNotFired(history);
  }

  /**
   * Verifies that a workflow task that cancels the fired timer without completing the workflow is
   * accepted, and the workflow is not wedged and keeps making progress. Previously the completion
   * failed with INVALID_ARGUMENT "invalid history builder state for action".
   */
  @Test
  public void cancelFiredTimerWithoutWorkflowCompletion() throws Exception {
    String workflowId = UUID.randomUUID().toString();
    PollWorkflowTaskQueueResponse task = startWorkflowAndPollTaskWithFiredTimer(workflowId);

    respondWorkflowTaskCompleted(task.getTaskToken(), cancelTimerCommand());
    List<HistoryEvent> history = getHistory(workflowId);
    assertTimerCancelledAndNotFired(history);

    // The workflow must still be able to progress and complete after the race.
    PollWorkflowTaskQueueResponse completionTask =
        TestServiceUtils.pollWorkflowTaskQueue(
            NAMESPACE, createNormalTaskQueue(TASK_QUEUE), workflowServiceStubs);
    respondWorkflowTaskCompleted(completionTask.getTaskToken(), completeWorkflowCommand());
    assertCompleted(getHistory(workflowId));
  }

  /**
   * Starts a workflow and brings it to a state where a timer fired while a workflow task is in
   * progress.
   *
   * <p>The first workflow task schedules an activity and starts a short timer. The outstanding
   * activity holds the time skipping lock, so the timer can only fire in real time. A signal then
   * schedules the second workflow task, and the method polls and starts it. While the second
   * workflow task is in progress, the timer fires in real time and its TIMER_FIRED event gets
   * buffered until the workflow task completion.
   *
   * @return the response of the started workflow task to complete with commands.
   */
  private PollWorkflowTaskQueueResponse startWorkflowAndPollTaskWithFiredTimer(String workflowId)
      throws Exception {
    StartWorkflowExecutionRequest startRequest =
        StartWorkflowExecutionRequest.newBuilder()
            .setRequestId(UUID.randomUUID().toString())
            .setNamespace(NAMESPACE)
            .setWorkflowId(workflowId)
            .setTaskQueue(createNormalTaskQueue(TASK_QUEUE))
            .setWorkflowRunTimeout(ProtobufTimeUtils.toProtoDuration(Duration.ofSeconds(100)))
            .setWorkflowTaskTimeout(ProtobufTimeUtils.toProtoDuration(Duration.ofSeconds(100)))
            .setWorkflowType(WorkflowType.newBuilder().setName(WORKFLOW_TYPE))
            .build();
    workflowServiceStubs.blockingStub().startWorkflowExecution(startRequest);

    PollWorkflowTaskQueueResponse firstTask =
        TestServiceUtils.pollWorkflowTaskQueue(
            NAMESPACE, createNormalTaskQueue(TASK_QUEUE), workflowServiceStubs);
    respondWorkflowTaskCompleted(
        firstTask.getTaskToken(), startTimerCommand(), scheduleActivityTaskCommand());

    // The signal schedules the second workflow task while the activity keeps the time skipping
    // locked, so the clock tracks real time from here on.
    TestServiceUtils.signalWorkflow(
        WorkflowExecution.newBuilder().setWorkflowId(workflowId).build(),
        NAMESPACE,
        workflowServiceStubs);
    PollWorkflowTaskQueueResponse secondTask =
        TestServiceUtils.pollWorkflowTaskQueue(
            NAMESPACE, createNormalTaskQueue(TASK_QUEUE), workflowServiceStubs);

    // Wait for the timer to fire in real time while the second workflow task is in progress. The
    // timer has a two second timeout and the sleep is four seconds long to leave a margin both
    // before the timer fires and for the test server to buffer the fired event.
    Thread.sleep(TIMER_FIRING_WAIT_MILLIS);
    return secondTask;
  }

  private void assertCompleted(List<HistoryEvent> history) {
    assertTrue(
        "Expected the workflow to complete",
        history.stream()
            .anyMatch(
                event ->
                    event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED));
  }

  private void assertTimerCancelledAndNotFired(List<HistoryEvent> history) {
    assertTrue(
        "Expected a TIMER_CANCELED event for the cancelled timer",
        history.stream()
            .anyMatch(
                event ->
                    event.getEventType() == EventType.EVENT_TYPE_TIMER_CANCELED
                        && event.getTimerCanceledEventAttributes().getTimerId().equals(TIMER_ID)));
    assertFalse(
        "The buffered TIMER_FIRED event of the cancelled timer should be replaced with the TIMER_CANCELED event",
        history.stream()
            .anyMatch(
                event ->
                    event.getEventType() == EventType.EVENT_TYPE_TIMER_FIRED
                        && event.getTimerFiredEventAttributes().getTimerId().equals(TIMER_ID)));
  }

  private List<HistoryEvent> getHistory(String workflowId) {
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(NAMESPACE)
            .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId))
            .build();
    return new ArrayList<>(
        workflowServiceStubs
            .blockingStub()
            .getWorkflowExecutionHistory(request)
            .getHistory()
            .getEventsList());
  }

  private void respondWorkflowTaskCompleted(ByteString taskToken, Command... commands) {
    RespondWorkflowTaskCompletedRequest request =
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .setTaskToken(taskToken)
            .addAllCommands(Arrays.asList(commands))
            .build();
    workflowServiceStubs.blockingStub().respondWorkflowTaskCompleted(request);
  }

  private Command startTimerCommand() {
    return Command.newBuilder()
        .setCommandType(CommandType.COMMAND_TYPE_START_TIMER)
        .setStartTimerCommandAttributes(
            StartTimerCommandAttributes.newBuilder()
                .setTimerId(TIMER_ID)
                .setStartToFireTimeout(ProtobufTimeUtils.toProtoDuration(TIMER_DURATION)))
        .build();
  }

  private Command cancelTimerCommand() {
    return Command.newBuilder()
        .setCommandType(CommandType.COMMAND_TYPE_CANCEL_TIMER)
        .setCancelTimerCommandAttributes(
            CancelTimerCommandAttributes.newBuilder().setTimerId(TIMER_ID))
        .build();
  }

  private Command completeWorkflowCommand() {
    return Command.newBuilder()
        .setCommandType(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION)
        .setCompleteWorkflowExecutionCommandAttributes(
            CompleteWorkflowExecutionCommandAttributes.newBuilder()
                .setResult(Payloads.getDefaultInstance()))
        .build();
  }

  private Command scheduleActivityTaskCommand() {
    return Command.newBuilder()
        .setCommandType(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK)
        .setScheduleActivityTaskCommandAttributes(
            ScheduleActivityTaskCommandAttributes.newBuilder()
                .setActivityId(ACTIVITY_ID)
                .setActivityType(ActivityType.newBuilder().setName("activity"))
                .setTaskQueue(TaskQueue.newBuilder().setName(TASK_QUEUE))
                .setScheduleToCloseTimeout(
                    ProtobufTimeUtils.toProtoDuration(Duration.ofSeconds(60))))
        .build();
  }
}
