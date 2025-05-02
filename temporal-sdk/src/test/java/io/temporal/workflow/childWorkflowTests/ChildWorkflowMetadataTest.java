package io.temporal.workflow.childWorkflowTests;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowExecutionDescription;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.TimerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.*;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;

public class ChildWorkflowMetadataTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestParentWorkflow.class, TestChild.class)
          .build();

  static final String summary = "my-wf-summary";
  static final String details = "my-wf-details";
  static final String childSummary = "child-summary";
  static final String childDetails = "child-details";
  static final String childTimerSummary = "child-timer-summary";

  @Test
  public void testChildWorkflowWithMetaData() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(20))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setStaticSummary(summary)
            .setStaticDetails(details)
            .build();
    TestWorkflow1 stub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflow1.class, options);

    String childWorkflowId = stub.execute(testWorkflowRule.getTaskQueue());

    WorkflowExecution exec = WorkflowStub.fromTyped(stub).getExecution();
    assertWorkflowMetadata(exec.getWorkflowId(), summary, details);

    WorkflowExecutionHistory workflowExecutionHistory =
        testWorkflowRule.getWorkflowClient().fetchHistory(exec.getWorkflowId());
    List<HistoryEvent> workflowStartedEvents =
        workflowExecutionHistory.getEvents().stream()
            .filter(HistoryEvent::hasWorkflowExecutionStartedEventAttributes)
            .collect(Collectors.toList());
    assertEventMetadata(workflowStartedEvents.get(0), summary, details);

    assertWorkflowMetadata(childWorkflowId, childSummary, childDetails);

    WorkflowExecutionHistory childWorkflowExecutionHistory =
        testWorkflowRule.getWorkflowClient().fetchHistory(childWorkflowId);
    List<HistoryEvent> childWorkflowStartedEvents =
        childWorkflowExecutionHistory.getEvents().stream()
            .filter(HistoryEvent::hasWorkflowExecutionStartedEventAttributes)
            .collect(Collectors.toList());
    assertEventMetadata(childWorkflowStartedEvents.get(0), childSummary, childDetails);

    List<HistoryEvent> timerStartedEvents =
        childWorkflowExecutionHistory.getEvents().stream()
            .filter(HistoryEvent::hasTimerStartedEventAttributes)
            .collect(Collectors.toList());
    assertEventMetadata(timerStartedEvents.get(0), childTimerSummary, null);
  }

  private void assertWorkflowMetadata(String workflowId, String summary, String details) {
    WorkflowExecutionDescription describe =
        testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub(workflowId).describe();
    assertEquals(summary, describe.getStaticSummary());
    assertEquals(details, describe.getStaticDetails());
  }

  private void assertEventMetadata(HistoryEvent event, String summary, String details) {
    if (summary != null) {
      String describedSummary =
          DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
              event.getUserMetadata().getSummary(), String.class, String.class);
      assertEquals(summary, describedSummary);
    }
    if (details != null) {
      String describedDetails =
          DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
              event.getUserMetadata().getDetails(), String.class, String.class);
      assertEquals(details, describedDetails);
    }
  }

  public static class TestParentWorkflow implements TestWorkflow1 {

    private final ITestChild child1 =
        Workflow.newChildWorkflowStub(
            ITestChild.class,
            ChildWorkflowOptions.newBuilder()
                .setStaticDetails(childDetails)
                .setStaticSummary(childSummary)
                .build());

    @Override
    public String execute(String taskQueue) {
      child1.execute("World!", 1);
      return Workflow.getWorkflowExecution(child1).get().getWorkflowId();
    }
  }

  public static class TestChild implements ITestChild {

    @Override
    public String execute(String arg, int delay) {
      Workflow.newTimer(
              Duration.ofMillis(delay),
              TimerOptions.newBuilder().setSummary(childTimerSummary).build())
          .get();
      return arg.toUpperCase();
    }
  }
}
