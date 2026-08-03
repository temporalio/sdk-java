package io.temporal.releaseautomation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import io.temporal.api.common.v1.Memo;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class ReleaseAutomationMainTest {
  @Test
  public void reportsUnrecoveredWorkflowTaskTimeout() {
    assertEquals(
        "workflow-task-failed-or-timed-out",
        ReleaseAutomationMain.unrecoveredWorkflowFailure(
            Arrays.asList(
                event(1, EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED),
                event(2, EventType.EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT))));
  }

  @Test
  public void laterWorkflowTaskCompletionRecoversTaskFailure() {
    assertNull(
        ReleaseAutomationMain.unrecoveredWorkflowFailure(
            Arrays.asList(
                event(1, EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED),
                event(2, EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED))));
  }

  @Test
  public void reportsTerminalWorkflowFailure() {
    assertEquals(
        "workflow-execution-failed",
        ReleaseAutomationMain.unrecoveredWorkflowFailure(
            Collections.singletonList(event(3, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED))));
  }

  @Test
  public void candidateStartReceiptRequiresExactExecutionMemoQueueAndInput() {
    CandidateIdentity candidate = ReleaseFixtures.candidate();
    WorkflowExecution execution =
        WorkflowExecution.newBuilder()
            .setWorkflowId(QueueNames.candidateWorkflowId(candidate))
            .setRunId("11111111-2222-3333-4444-555555555555")
            .build();
    WorkflowExecutionMetadata description = candidateDescription(candidate, execution);
    WorkflowExecutionStartedEventAttributes started = candidateStart(candidate);

    ReleaseAutomationMain.validateCandidateStart(
        execution, description, started, candidate, candidate);

    WorkflowExecution wrongRun = execution.toBuilder().setRunId("different-run-0000").build();
    assertThrows(
        IllegalStateException.class,
        () ->
            ReleaseAutomationMain.validateCandidateStart(
                wrongRun, description, started, candidate, candidate));
    CandidateIdentity wrongInput = ReleaseFixtures.candidate();
    wrongInput.commitSha = "ffffffffffffffffffffffffffffffffffffffff";
    assertThrows(
        IllegalStateException.class,
        () ->
            ReleaseAutomationMain.validateCandidateStart(
                execution, description, started, wrongInput, candidate));
  }

  @Test
  public void releaseParentRequiresExactCandidateRunReceipt() {
    ReleaseIdentity release = ReleaseFixtures.release();
    WorkflowExecution candidateExecution =
        WorkflowExecution.newBuilder()
            .setWorkflowId(QueueNames.candidateWorkflowId(release.candidate))
            .setRunId(release.candidateRunId)
            .build();
    WorkflowExecutionInfo info =
        WorkflowExecutionInfo.newBuilder()
            .setExecution(
                WorkflowExecution.newBuilder()
                    .setWorkflowId(QueueNames.releaseWorkflowId(release))
                    .setRunId("release-run-0000"))
            .setParentExecution(candidateExecution)
            .setRootExecution(candidateExecution)
            .build();
    WorkflowExecutionMetadata metadata =
        new WorkflowExecutionMetadata(info, DefaultDataConverter.STANDARD_INSTANCE);
    ReleaseAutomationMain.validateReleaseParent(metadata, release);

    release.candidateRunId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    assertThrows(
        IllegalStateException.class,
        () -> ReleaseAutomationMain.validateReleaseParent(metadata, release));
  }

  private static HistoryEvent event(long id, EventType type) {
    return HistoryEvent.newBuilder().setEventId(id).setEventType(type).build();
  }

  private static WorkflowExecutionMetadata candidateDescription(
      CandidateIdentity candidate, WorkflowExecution execution) {
    DataConverter converter = DefaultDataConverter.STANDARD_INSTANCE;
    Memo memo =
        Memo.newBuilder()
            .putFields("CandidateIdentity", converter.toPayload(candidate).get())
            .build();
    WorkflowExecutionInfo info =
        WorkflowExecutionInfo.newBuilder()
            .setExecution(execution)
            .setType(WorkflowType.newBuilder().setName("CandidateWorkflow"))
            .setTaskQueue(QueueNames.candidateWorkflow(candidate))
            .setMemo(memo)
            .build();
    return new WorkflowExecutionMetadata(info, converter);
  }

  private static WorkflowExecutionStartedEventAttributes candidateStart(
      CandidateIdentity candidate) {
    return WorkflowExecutionStartedEventAttributes.newBuilder()
        .setWorkflowType(WorkflowType.newBuilder().setName("CandidateWorkflow"))
        .setTaskQueue(TaskQueue.newBuilder().setName(QueueNames.candidateWorkflow(candidate)))
        .setInput(DefaultDataConverter.STANDARD_INSTANCE.toPayloads(candidate).get())
        .build();
  }
}
