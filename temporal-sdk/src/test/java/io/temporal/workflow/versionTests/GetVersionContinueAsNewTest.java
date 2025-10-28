package io.temporal.workflow.versionTests;

import static io.temporal.internal.history.VersionMarkerUtils.TEMPORAL_CHANGE_VERSION;
import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowTargetOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.internal.history.VersionMarkerUtils;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionContinueAsNewTest extends BaseVersionTest {
  public GetVersionContinueAsNewTest(boolean setVersioningFlag, boolean upsertVersioningSA) {
    super(setVersioningFlag, upsertVersioningSA);
  }

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(getDefaultWorkflowImplementationOptions(), TestWorkflowImpl.class)
          .build();

  @Test
  public void versionNotCarriedOverOnContinueAsNew() {
    TestWorkflow workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow.class);
    // Start workflow to obtain the first run id
    WorkflowExecution run1 = WorkflowClient.start(workflow::execute, 2);
    // Wait for workflow completion
    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    untyped.getResult(Void.class);
    // Point the untyped stub to the latest execution
    untyped =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(
                Optional.empty(),
                WorkflowTargetOptions.newBuilder().setWorkflowExecution(run1).build());
    WorkflowStub latestUntyped =
        testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub(run1.getWorkflowId());
    WorkflowExecution run2 = latestUntyped.getExecution();

    WorkflowExecutionHistory history1 =
        testWorkflowRule.getWorkflowClient().fetchHistory(run1.getWorkflowId(), run1.getRunId());
    List<HistoryEvent> markers1 =
        history1.getEvents().stream()
            .filter(e -> e.getEventType() == EventType.EVENT_TYPE_MARKER_RECORDED)
            .collect(Collectors.toList());
    assertEquals(1, markers1.size());
    assertEquals(
        2,
        VersionMarkerUtils.getVersion(markers1.get(0).getMarkerRecordedEventAttributes())
            .intValue());
    if (upsertVersioningSA) {
      assertEquals(
          Collections.singletonList("change-2"),
          untyped.describe().getTypedSearchAttributes().get(TEMPORAL_CHANGE_VERSION));
    }

    WorkflowExecutionHistory history2 =
        testWorkflowRule.getWorkflowClient().fetchHistory(run2.getWorkflowId(), run2.getRunId());
    List<HistoryEvent> markers2 =
        history2.getEvents().stream()
            .filter(e -> e.getEventType() == EventType.EVENT_TYPE_MARKER_RECORDED)
            .collect(Collectors.toList());
    assertEquals(1, markers2.size());
    assertEquals(
        1,
        VersionMarkerUtils.getVersion(markers2.get(0).getMarkerRecordedEventAttributes())
            .intValue());
    if (upsertVersioningSA) {
      assertEquals(
          Collections.singletonList("change-1"),
          latestUntyped.describe().getTypedSearchAttributes().get(TEMPORAL_CHANGE_VERSION));
    }
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    void execute(int runs);
  }

  public static class TestWorkflowImpl implements TestWorkflow {
    @Override
    public void execute(int runs) {
      int version = Workflow.getVersion("change", Workflow.DEFAULT_VERSION, runs);
      assertEquals(runs, version);
      if (runs > 1) {
        TestWorkflow next = Workflow.newContinueAsNewStub(TestWorkflow.class);
        next.execute(runs - 1);
      }
    }
  }
}
