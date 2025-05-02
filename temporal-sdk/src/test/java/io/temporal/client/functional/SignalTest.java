package io.temporal.client.functional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;

public class SignalTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(QuickWorkflowWithSignalImpl.class).build();

  @Test
  public void signalNonExistentWorkflow() {
    TestWorkflows.TestSignaledWorkflow workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.TestSignaledWorkflow.class, "non-existing-id");
    assertThrows(WorkflowNotFoundException.class, () -> workflow.signal("some-value"));
  }

  @Test
  public void signalCompletedWorkflow() {
    TestWorkflows.TestSignaledWorkflow workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.TestSignaledWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build());
    workflow.execute();
    assertThrows(WorkflowNotFoundException.class, () -> workflow.signal("some-value"));
  }

  @Test(timeout = 50000)
  public void signalWithStartWithDelay() {
    WorkflowOptions workflowOptions =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setStartDelay(Duration.ofSeconds(5))
            .build();
    WorkflowStub stubF =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub("TestSignaledWorkflow", workflowOptions);

    WorkflowExecution workflowExecution =
        stubF.signalWithStart("testSignal", new Object[] {"testArg"}, new Object[] {});

    assertEquals("done", stubF.getResult(String.class));
    WorkflowExecutionHistory workflowExecutionHistory =
        testWorkflowRule.getWorkflowClient().fetchHistory(workflowExecution.getWorkflowId());
    List<WorkflowExecutionStartedEventAttributes> workflowExecutionStartedEvents =
        workflowExecutionHistory.getEvents().stream()
            .filter(HistoryEvent::hasWorkflowExecutionStartedEventAttributes)
            .map(x -> x.getWorkflowExecutionStartedEventAttributes())
            .collect(Collectors.toList());
    assertEquals(1, workflowExecutionStartedEvents.size());
    assertEquals(
        Duration.ofSeconds(5),
        ProtobufTimeUtils.toJavaDuration(
            workflowExecutionStartedEvents.get(0).getFirstWorkflowTaskBackoff()));
  }

  public static class QuickWorkflowWithSignalImpl implements TestWorkflows.TestSignaledWorkflow {

    @Override
    public String execute() {
      return "done";
    }

    @Override
    public void signal(String arg) {}
  }
}
