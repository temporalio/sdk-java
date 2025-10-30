package io.temporal.workflow.activityTests;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testUtils.HistoryUtils;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;

public class LocalActivityMetadataTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new TestActivities.TestActivitiesImpl())
          .build();

  static final String localActivitySummary = "local-activity-summary";

  @Test
  public void testLocalActivityWithMetaData() {
    TestWorkflow1 stub = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    stub.execute(testWorkflowRule.getTaskQueue());

    WorkflowExecution exec = WorkflowStub.fromTyped(stub).getExecution();
    WorkflowExecutionHistory workflowExecutionHistory =
        testWorkflowRule.getWorkflowClient().fetchHistory(exec.getWorkflowId());
    List<HistoryEvent> localActivityScheduledEvents =
        workflowExecutionHistory.getEvents().stream()
            .filter(HistoryEvent::hasMarkerRecordedEventAttributes)
            .collect(Collectors.toList());
    HistoryUtils.assertEventMetadata(
        localActivityScheduledEvents.get(0), localActivitySummary, null);
  }

  public static class TestWorkflowImpl implements TestWorkflow1 {

    private final TestActivities.VariousTestActivities activities =
        Workflow.newLocalActivityStub(
            TestActivities.VariousTestActivities.class,
            LocalActivityOptions.newBuilder()
                .setSummary(localActivitySummary)
                .setStartToCloseTimeout(Duration.ofSeconds(5))
                .build());

    @Override
    public String execute(String taskQueue) {
      return activities.activity();
    }
  }
}
