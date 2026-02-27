package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testUtils.HistoryUtils;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import io.temporal.workflow.shared.TestWorkflows.TestTraceWorkflow;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;

public class SleepTest {

  static final String SLEEP_SUMMARY = "sleep1";

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestSleepWorkflowImpl.class).build();

  @Test
  public void testSleep() {
    WorkflowOptions options;
    if (testWorkflowRule.isUseExternalService()) {
      options = SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue());
    } else {
      options =
          SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
              .setWorkflowRunTimeout(Duration.ofDays(1))
              .build();
    }
    TestTraceWorkflow client =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestTraceWorkflow.class, options);
    String result = client.execute();
    assertEquals("testSleep", result);

    // Validate that the timer summary was actually set in the history
    WorkflowExecution exec = WorkflowStub.fromTyped(client).getExecution();
    WorkflowExecutionHistory history =
        testWorkflowRule.getWorkflowClient().fetchHistory(exec.getWorkflowId());
    List<HistoryEvent> timerStartedEvents =
        history.getEvents().stream()
            .filter(HistoryEvent::hasTimerStartedEventAttributes)
            .collect(Collectors.toList());
    assertEquals(1, timerStartedEvents.size());
    HistoryUtils.assertEventMetadata(timerStartedEvents.get(0), SLEEP_SUMMARY, null);

    if (testWorkflowRule.isUseExternalService()) {
      testWorkflowRule
          .getInterceptor(TracingWorkerInterceptor.class)
          .setExpected(
              "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
              "registerQuery getTrace",
              "newThread workflow-method",
              "currentTimeMillis",
              "sleep PT0.7S",
              "currentTimeMillis");
    } else {
      testWorkflowRule
          .getInterceptor(TracingWorkerInterceptor.class)
          .setExpected(
              "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
              "registerQuery getTrace",
              "newThread workflow-method",
              "currentTimeMillis",
              "sleep PT11M40S",
              "currentTimeMillis");
    }
  }

  public static class TestSleepWorkflowImpl implements TestTraceWorkflow {

    @Override
    public String execute() {
      boolean useExternalService = SDKTestWorkflowRule.useExternalService;
      Duration timeout1 = useExternalService ? Duration.ofMillis(700) : Duration.ofSeconds(700);
      long time = Workflow.currentTimeMillis();
      Workflow.sleep(timeout1, TimerOptions.newBuilder().setSummary(SLEEP_SUMMARY).build());
      long slept = Workflow.currentTimeMillis() - time;
      // Also checks that rounding up to a second works.
      assertTrue(slept + "<" + timeout1.toMillis(), slept >= timeout1.toMillis());
      return "testSleep";
    }

    @Override
    public List<String> getTrace() {
      throw new UnsupportedOperationException("not implemented");
    }
  }
}
