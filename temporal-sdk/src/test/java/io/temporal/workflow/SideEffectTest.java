package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testUtils.HistoryUtils;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SideEffectTest {
  static final String sideEffectSummary = "side-effect-summary";

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestSideEffectWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          .build();

  @Test
  public void testSideEffect() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("activity1", result);
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "currentTimeMillis",
            "sideEffect",
            "sideEffect",
            "sleep PT1S",
            "executeActivity customActivity1",
            "activity customActivity1");

    WorkflowExecution exec = WorkflowStub.fromTyped(workflowStub).getExecution();
    WorkflowExecutionHistory workflowExecutionHistory =
        testWorkflowRule.getWorkflowClient().fetchHistory(exec.getWorkflowId());
    List<HistoryEvent> sideEffectMarkerEvents =
        workflowExecutionHistory.getEvents().stream()
            .filter(HistoryEvent::hasMarkerRecordedEventAttributes)
            .collect(Collectors.toList());
    HistoryUtils.assertEventMetadata(sideEffectMarkerEvents.get(0), sideEffectSummary, null);
  }

  public static class TestSideEffectWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      VariousTestActivities testActivities =
          Workflow.newActivityStub(
              VariousTestActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));

      long workflowTime = Workflow.currentTimeMillis();
      long time1 =
          Workflow.sideEffect(
              long.class,
              () -> workflowTime,
              SideEffectOptions.newBuilder().setSummary(sideEffectSummary).build());
      long time2 = Workflow.sideEffect(long.class, () -> workflowTime);
      assertEquals(time1, time2);
      Workflow.sleep(Duration.ofSeconds(1));
      String result;
      if (workflowTime == time1) {
        result = "activity" + testActivities.activity1(1);
      } else {
        result = testActivities.activity2("activity2", 2);
      }
      return result;
    }
  }
}
