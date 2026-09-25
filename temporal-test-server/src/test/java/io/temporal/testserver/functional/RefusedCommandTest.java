package io.temporal.testserver.functional;

import static io.temporal.internal.common.InternalUtils.createNormalTaskQueue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.command.v1.CancelTimerCommandAttributes;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.CompleteWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.ModifyWorkflowPropertiesCommandAttributes;
import io.temporal.api.command.v1.ProtocolMessageCommandAttributes;
import io.temporal.api.command.v1.RequestCancelActivityTaskCommandAttributes;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.command.v1.SignalExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.StartTimerCommandAttributes;
import io.temporal.api.command.v1.UpsertWorkflowSearchAttributesCommandAttributes;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Memo;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.WorkflowTaskFailedCause;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.TestServiceUtils;
import io.temporal.testserver.TestServer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A workflow task completion that cancels a timer or an activity the server does not know about
 * must fail the workflow task and schedule a new one, the way the real server does. Before the fix
 * the test server refused the completion after it had already moved the workflow task state machine
 * to NONE, so the task was never timed out or redelivered and the run hung forever.
 */
public class RefusedCommandTest {

  private static final String NAMESPACE = "namespace";
  private static final String TASK_QUEUE = "taskQueue";
  private static final String WORKFLOW_TYPE = "wfType";

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

  @Test
  public void cancelTimerForUnknownTimerFailsWorkflowTaskAndReschedules() throws Exception {
    PollWorkflowTaskQueueResponse task = startWorkflowAndPollFirstTask();

    Command cancelUnknownTimer =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_CANCEL_TIMER)
            .setCancelTimerCommandAttributes(
                CancelTimerCommandAttributes.newBuilder().setTimerId("no-such-timer"))
            .build();

    StatusRuntimeException e =
        assertThrows(
            StatusRuntimeException.class,
            () -> respondWorkflowTaskCompleted(task.getTaskToken(), cancelUnknownTimer));
    assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());

    List<HistoryEvent> history = getHistory(task);
    assertWorkflowTaskFailedAndRescheduled(
        history, WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_BAD_CANCEL_TIMER_ATTRIBUTES);
    assertFalse(
        "no timer was canceled",
        history.stream().anyMatch(ev -> ev.getEventType() == EventType.EVENT_TYPE_TIMER_CANCELED));

    assertWorkflowTaskRedeliveredAndCompletes(task);
  }

  @Test
  public void requestCancelActivityForUnknownActivityFailsWorkflowTaskAndReschedules()
      throws Exception {
    PollWorkflowTaskQueueResponse task = startWorkflowAndPollFirstTask();

    Command cancelUnknownActivity =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_REQUEST_CANCEL_ACTIVITY_TASK)
            .setRequestCancelActivityTaskCommandAttributes(
                RequestCancelActivityTaskCommandAttributes.newBuilder().setScheduledEventId(12345))
            .build();

    StatusRuntimeException e =
        assertThrows(
            StatusRuntimeException.class,
            () -> respondWorkflowTaskCompleted(task.getTaskToken(), cancelUnknownActivity));
    assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());

    assertWorkflowTaskFailedAndRescheduled(
        getHistory(task),
        WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_BAD_REQUEST_CANCEL_ACTIVITY_ATTRIBUTES);

    assertWorkflowTaskRedeliveredAndCompletes(task);
  }

  @Test
  public void scheduleActivityWithoutTaskQueueFailsWorkflowTaskAndReschedules() throws Exception {
    PollWorkflowTaskQueueResponse task = startWorkflowAndPollFirstTask();

    Command scheduleWithoutTaskQueue =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK)
            .setScheduleActivityTaskCommandAttributes(
                ScheduleActivityTaskCommandAttributes.newBuilder()
                    .setActivityId("activity")
                    .setActivityType(ActivityType.newBuilder().setName("activity"))
                    .setScheduleToCloseTimeout(
                        ProtobufTimeUtils.toProtoDuration(Duration.ofSeconds(60))))
            .build();

    StatusRuntimeException e =
        assertThrows(
            StatusRuntimeException.class,
            () -> respondWorkflowTaskCompleted(task.getTaskToken(), scheduleWithoutTaskQueue));
    assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());

    assertWorkflowTaskFailedAndRescheduled(
        getHistory(task),
        WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_BAD_SCHEDULE_ACTIVITY_ATTRIBUTES);

    assertWorkflowTaskRedeliveredAndCompletes(task);
  }

  @Test
  public void startTimerWithDuplicateIdFailsWorkflowTaskAndReschedules() throws Exception {
    PollWorkflowTaskQueueResponse firstTask = startWorkflowAndPollFirstTask();
    respondWorkflowTaskCompleted(firstTask.getTaskToken(), startTimerCommand("timer"));

    TestServiceUtils.signalWorkflow(
        firstTask.getWorkflowExecution(), NAMESPACE, workflowServiceStubs);
    PollWorkflowTaskQueueResponse secondTask =
        TestServiceUtils.pollWorkflowTaskQueue(
            NAMESPACE, createNormalTaskQueue(TASK_QUEUE), workflowServiceStubs);

    StatusRuntimeException e =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                respondWorkflowTaskCompleted(
                    secondTask.getTaskToken(), startTimerCommand("timer")));
    assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());

    assertWorkflowTaskFailedAndRescheduled(
        getHistory(firstTask),
        WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_START_TIMER_DUPLICATE_ID);

    assertWorkflowTaskRedeliveredAndCompletes(firstTask);
  }

  @Test
  public void commandsBeforeTheRefusedOneAreUndoneSoTheReplayIsAccepted() throws Exception {
    PollWorkflowTaskQueueResponse task = startWorkflowAndPollFirstTask();

    Command cancelUnknownTimer =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_CANCEL_TIMER)
            .setCancelTimerCommandAttributes(
                CancelTimerCommandAttributes.newBuilder().setTimerId("no-such-timer"))
            .build();
    assertThrows(
        StatusRuntimeException.class,
        () ->
            respondWorkflowTaskCompleted(
                task.getTaskToken(), startTimerCommand("timer"), cancelUnknownTimer));
    assertWorkflowTaskFailedAndRescheduled(
        getHistory(task),
        WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_BAD_CANCEL_TIMER_ATTRIBUTES);

    // The worker replays from history, which has no timer, so it sends StartTimer again. The
    // timer the refused completion added must be gone or this is rejected as a duplicate.
    PollWorkflowTaskQueueResponse redelivered = pollWorkflowTask();
    respondWorkflowTaskCompleted(redelivered.getTaskToken(), startTimerCommand("timer"));
    assertTrue(
        "expected the replayed StartTimer to be accepted, history: " + eventTypes(getHistory(task)),
        getHistory(task).stream()
            .anyMatch(ev -> ev.getEventType() == EventType.EVENT_TYPE_TIMER_STARTED));
  }

  @Test
  public void requestCancelActivityThatFinishedDuringTheTaskIsAccepted() throws Exception {
    PollWorkflowTaskQueueResponse firstTask = startWorkflowAndPollFirstTask();
    respondWorkflowTaskCompleted(firstTask.getTaskToken(), scheduleActivityTaskCommand());

    // A signal schedules a second workflow task; poll it so it is in flight.
    TestServiceUtils.signalWorkflow(
        firstTask.getWorkflowExecution(), NAMESPACE, workflowServiceStubs);
    PollWorkflowTaskQueueResponse secondTask =
        TestServiceUtils.pollWorkflowTaskQueue(
            NAMESPACE, createNormalTaskQueue(TASK_QUEUE), workflowServiceStubs);

    // Complete the activity while the second workflow task is running, so its completion event
    // is buffered and the activity no longer has a state machine.
    PollActivityTaskQueueResponse activityTask =
        workflowServiceStubs
            .blockingStub()
            .pollActivityTaskQueue(
                PollActivityTaskQueueRequest.newBuilder()
                    .setNamespace(NAMESPACE)
                    .setTaskQueue(createNormalTaskQueue(TASK_QUEUE))
                    .build());
    long scheduledEventId =
        getHistory(firstTask).stream()
            .filter(ev -> ev.getEventType() == EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED)
            .findFirst()
            .orElseThrow(() -> new AssertionError("activity was not scheduled"))
            .getEventId();
    workflowServiceStubs
        .blockingStub()
        .respondActivityTaskCompleted(
            RespondActivityTaskCompletedRequest.newBuilder()
                .setTaskToken(activityTask.getTaskToken())
                .build());

    // The worker, which has not seen the completion yet, cancels the activity.
    Command cancelActivity =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_REQUEST_CANCEL_ACTIVITY_TASK)
            .setRequestCancelActivityTaskCommandAttributes(
                RequestCancelActivityTaskCommandAttributes.newBuilder()
                    .setScheduledEventId(scheduledEventId))
            .build();
    respondWorkflowTaskCompleted(secondTask.getTaskToken(), cancelActivity);

    List<EventType> events = eventTypes(getHistory(firstTask));
    assertFalse(
        "the cancel must not fail the workflow task, history: " + events,
        events.contains(EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED));
    int cancelRequested = events.indexOf(EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED);
    int completed = events.indexOf(EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED);
    assertTrue("expected ActivityTaskCancelRequested, history: " + events, cancelRequested >= 0);
    assertTrue(
        "the buffered completion must follow the cancel request, history: " + events,
        completed > cancelRequested);
  }

  @Test
  public void searchAttributesAndMemoOfARefusedCompletionAreNotApplied() throws Exception {
    PollWorkflowTaskQueueResponse task = startWorkflowAndPollFirstTask();
    Payload one = DefaultDataConverter.newDefaultInstance().toPayload(1).get();
    Command upsert =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES)
            .setUpsertWorkflowSearchAttributesCommandAttributes(
                UpsertWorkflowSearchAttributesCommandAttributes.newBuilder()
                    .setSearchAttributes(
                        SearchAttributes.newBuilder().putIndexedFields("CustomIntField", one)))
            .build();
    Command memo =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_MODIFY_WORKFLOW_PROPERTIES)
            .setModifyWorkflowPropertiesCommandAttributes(
                ModifyWorkflowPropertiesCommandAttributes.newBuilder()
                    .setUpsertedMemo(Memo.newBuilder().putFields("memoKey", one)))
            .build();

    assertThrows(
        StatusRuntimeException.class,
        () ->
            respondWorkflowTaskCompleted(
                task.getTaskToken(), upsert, memo, cancelTimerCommand("no-such-timer")));
    assertWorkflowTaskFailedAndRescheduled(
        getHistory(task),
        WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_BAD_CANCEL_TIMER_ATTRIBUTES);

    // Neither effect of the refused completion is visible: not in history, not in Describe.
    List<EventType> types = eventTypes(getHistory(task));
    assertFalse(types.contains(EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES));
    assertFalse(types.contains(EventType.EVENT_TYPE_WORKFLOW_PROPERTIES_MODIFIED));
    DescribeWorkflowExecutionResponse described = describe(task.getWorkflowExecution());
    assertFalse(
        described
            .getWorkflowExecutionInfo()
            .getSearchAttributes()
            .containsIndexedFields("CustomIntField"));
    assertFalse(described.getWorkflowExecutionInfo().getMemo().containsFields("memoKey"));

    // The same changes on the redelivered task are applied.
    PollWorkflowTaskQueueResponse redelivered = pollWorkflowTask();
    respondWorkflowTaskCompleted(redelivered.getTaskToken(), upsert, memo);
    described = describe(task.getWorkflowExecution());
    // The store adds type metadata to the stored value, so compare the data only.
    assertEquals(
        one.getData(),
        described
            .getWorkflowExecutionInfo()
            .getSearchAttributes()
            .getIndexedFieldsOrThrow("CustomIntField")
            .getData());
    assertEquals(one, described.getWorkflowExecutionInfo().getMemo().getFieldsOrThrow("memoKey"));
  }

  @Test
  public void aRefusedCompletionDoesNotSignalTheExternalWorkflow() throws Exception {
    PollWorkflowTaskQueueResponse task = startWorkflowAndPollFirstTask();
    String targetId = "target-" + UUID.randomUUID();
    workflowServiceStubs
        .blockingStub()
        .startWorkflowExecution(
            StartWorkflowExecutionRequest.newBuilder()
                .setNamespace(NAMESPACE)
                .setRequestId(UUID.randomUUID().toString())
                .setWorkflowId(targetId)
                .setWorkflowType(WorkflowType.newBuilder().setName(WORKFLOW_TYPE))
                .setTaskQueue(TaskQueue.newBuilder().setName("target-" + TASK_QUEUE))
                .setWorkflowRunTimeout(ProtobufTimeUtils.toProtoDuration(Duration.ofSeconds(100)))
                .setWorkflowTaskTimeout(ProtobufTimeUtils.toProtoDuration(Duration.ofSeconds(100)))
                .build());
    WorkflowExecution target = WorkflowExecution.newBuilder().setWorkflowId(targetId).build();
    Command signal =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION)
            .setSignalExternalWorkflowExecutionCommandAttributes(
                SignalExternalWorkflowExecutionCommandAttributes.newBuilder()
                    .setExecution(target)
                    .setSignalName("signal"))
            .build();

    assertThrows(
        StatusRuntimeException.class,
        () ->
            respondWorkflowTaskCompleted(
                task.getTaskToken(), signal, cancelTimerCommand("no-such-timer")));
    assertWorkflowTaskFailedAndRescheduled(
        getHistory(task),
        WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_BAD_CANCEL_TIMER_ATTRIBUTES);

    // The signal was dispatched on commit, and the refused completion never committed. Delivery is
    // asynchronous, so give a wrongly dispatched signal time to land before checking.
    Thread.sleep(500);
    assertFalse(
        eventTypes(getHistory(task))
            .contains(EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED));
    assertFalse(
        eventTypes(getHistory(target)).contains(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED));

    // The same signal on the redelivered task is delivered.
    PollWorkflowTaskQueueResponse redelivered = pollWorkflowTask();
    respondWorkflowTaskCompleted(redelivered.getTaskToken(), signal);
    long deadline = System.currentTimeMillis() + 5_000;
    while (!eventTypes(getHistory(target))
            .contains(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED)
        && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    assertTrue(
        "expected the target to be signaled, history: " + eventTypes(getHistory(target)),
        eventTypes(getHistory(target)).contains(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED));
  }

  @Test
  public void aProtocolMessageForAnUnknownMessageFailsTheTaskWithTheUpdateCause() throws Exception {
    PollWorkflowTaskQueueResponse task = startWorkflowAndPollFirstTask();
    Command message =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_PROTOCOL_MESSAGE)
            .setProtocolMessageCommandAttributes(
                ProtocolMessageCommandAttributes.newBuilder().setMessageId("no-such-message"))
            .build();

    StatusRuntimeException e =
        assertThrows(
            StatusRuntimeException.class,
            () -> respondWorkflowTaskCompleted(task.getTaskToken(), message));
    assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());
    assertWorkflowTaskFailedAndRescheduled(
        getHistory(task),
        WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_BAD_UPDATE_WORKFLOW_EXECUTION_MESSAGE);
    assertWorkflowTaskRedeliveredAndCompletes(task);
  }

  private Command cancelTimerCommand(String timerId) {
    return Command.newBuilder()
        .setCommandType(CommandType.COMMAND_TYPE_CANCEL_TIMER)
        .setCancelTimerCommandAttributes(
            CancelTimerCommandAttributes.newBuilder().setTimerId(timerId))
        .build();
  }

  private DescribeWorkflowExecutionResponse describe(WorkflowExecution execution) {
    return workflowServiceStubs
        .blockingStub()
        .describeWorkflowExecution(
            DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(NAMESPACE)
                .setExecution(execution)
                .build());
  }

  private List<HistoryEvent> getHistory(WorkflowExecution execution) {
    return workflowServiceStubs
        .blockingStub()
        .getWorkflowExecutionHistory(
            GetWorkflowExecutionHistoryRequest.newBuilder()
                .setNamespace(NAMESPACE)
                .setExecution(execution)
                .build())
        .getHistory()
        .getEventsList();
  }

  private Command startTimerCommand(String timerId) {
    return Command.newBuilder()
        .setCommandType(CommandType.COMMAND_TYPE_START_TIMER)
        .setStartTimerCommandAttributes(
            StartTimerCommandAttributes.newBuilder()
                .setTimerId(timerId)
                .setStartToFireTimeout(ProtobufTimeUtils.toProtoDuration(Duration.ofHours(1))))
        .build();
  }

  private Command scheduleActivityTaskCommand() {
    return Command.newBuilder()
        .setCommandType(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK)
        .setScheduleActivityTaskCommandAttributes(
            ScheduleActivityTaskCommandAttributes.newBuilder()
                .setActivityId("activity")
                .setActivityType(ActivityType.newBuilder().setName("activity"))
                .setTaskQueue(TaskQueue.newBuilder().setName(TASK_QUEUE))
                .setScheduleToCloseTimeout(
                    ProtobufTimeUtils.toProtoDuration(Duration.ofSeconds(60))))
        .build();
  }

  private PollWorkflowTaskQueueResponse startWorkflowAndPollFirstTask() throws Exception {
    TestServiceUtils.startWorkflowExecution(
        NAMESPACE, TASK_QUEUE, WORKFLOW_TYPE, workflowServiceStubs);
    return TestServiceUtils.pollWorkflowTaskQueue(
        NAMESPACE, createNormalTaskQueue(TASK_QUEUE), workflowServiceStubs);
  }

  private void assertWorkflowTaskFailedAndRescheduled(
      List<HistoryEvent> history, WorkflowTaskFailedCause expectedCause) {
    int failedIndex = -1;
    for (int i = 0; i < history.size(); i++) {
      if (history.get(i).getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED) {
        failedIndex = i;
        break;
      }
    }
    assertTrue(
        "expected a WorkflowTaskFailed event, history: " + eventTypes(history), failedIndex >= 0);
    assertEquals(
        expectedCause, history.get(failedIndex).getWorkflowTaskFailedEventAttributes().getCause());
    assertTrue(
        "expected a new WorkflowTaskScheduled after the failure, history: " + eventTypes(history),
        history.subList(failedIndex + 1, history.size()).stream()
            .anyMatch(ev -> ev.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_SCHEDULED));
  }

  /** The failed task must be redelivered, and the run must still be able to complete. */
  private PollWorkflowTaskQueueResponse pollWorkflowTask() {
    PollWorkflowTaskQueueResponse task =
        workflowServiceStubs
            .blockingStub()
            .withDeadlineAfter(10, TimeUnit.SECONDS)
            .pollWorkflowTaskQueue(
                PollWorkflowTaskQueueRequest.newBuilder()
                    .setNamespace(NAMESPACE)
                    .setTaskQueue(createNormalTaskQueue(TASK_QUEUE))
                    .build());
    assertFalse("expected a workflow task to be delivered", task.getTaskToken().isEmpty());
    return task;
  }

  private void assertWorkflowTaskRedeliveredAndCompletes(PollWorkflowTaskQueueResponse firstTask)
      throws Exception {
    PollWorkflowTaskQueueResponse redelivered = pollWorkflowTask();

    Command complete =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION)
            .setCompleteWorkflowExecutionCommandAttributes(
                CompleteWorkflowExecutionCommandAttributes.getDefaultInstance())
            .build();
    respondWorkflowTaskCompleted(redelivered.getTaskToken(), complete);

    assertTrue(
        "expected the workflow to complete after the redelivered task",
        getHistory(firstTask).stream()
            .anyMatch(
                ev -> ev.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED));
  }

  private void respondWorkflowTaskCompleted(ByteString taskToken, Command... commands) {
    RespondWorkflowTaskCompletedRequest.Builder request =
        RespondWorkflowTaskCompletedRequest.newBuilder().setTaskToken(taskToken);
    for (Command command : commands) {
      request.addCommands(command);
    }
    workflowServiceStubs.blockingStub().respondWorkflowTaskCompleted(request.build());
  }

  private List<HistoryEvent> getHistory(PollWorkflowTaskQueueResponse task) {
    return workflowServiceStubs
        .blockingStub()
        .getWorkflowExecutionHistory(
            GetWorkflowExecutionHistoryRequest.newBuilder()
                .setNamespace(NAMESPACE)
                .setExecution(task.getWorkflowExecution())
                .build())
        .getHistory()
        .getEventsList();
  }

  private static List<EventType> eventTypes(List<HistoryEvent> history) {
    return history.stream()
        .map(HistoryEvent::getEventType)
        .collect(java.util.stream.Collectors.toList());
  }
}
