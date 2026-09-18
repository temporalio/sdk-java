package io.temporal.opentelemetry.v2;

import static org.junit.Assert.assertEquals;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.Collection;
import org.junit.Rule;
import org.junit.Test;

/**
 * Verifies that workflow and activity metrics record during live execution and that replaying a
 * workflow run does not emit duplicate metrics.
 */
public class MetricsTest extends OtelTestBase {
  private static final String METER_NAME = "custom-metrics";
  private static final String WORKFLOW_COUNTER = "custom_workflow_counter";
  private static final String ACTIVITY_COUNTER = "custom_activity_counter";

  @ActivityInterface
  public interface TestActivity {
    @ActivityMethod
    void doActivity();
  }

  public static class TestActivityImpl implements TestActivity {
    @Override
    public void doActivity() {
      GlobalOpenTelemetry.getMeter(METER_NAME).counterBuilder(ACTIVITY_COUNTER).build().add(1);
    }
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    void run();

    @SignalMethod
    void proceed();
  }

  public static class TestWorkflowImpl implements TestWorkflow {
    private boolean proceed;

    @Override
    public void run() {
      GlobalOpenTelemetry.getMeter(METER_NAME).counterBuilder(WORKFLOW_COUNTER).build().add(1);

      TestActivity activity =
          Workflow.newActivityStub(
              TestActivity.class,
              ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build());
      activity.doActivity();

      Workflow.await(() -> proceed);
    }

    @Override
    public void proceed() {
      proceed = true;
    }
  }

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      newRuleBuilder(false)
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new TestActivityImpl())
          .build();

  @Test
  public void liveExecutionRecordsAndReplayDoesNotDuplicate() throws Exception {
    TestWorkflow workflow = testWorkflowRule.newWorkflowStub(TestWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::run);

    workflow.proceed();
    WorkflowStub.fromTyped(workflow).getResult(Void.class);

    assertEquals(1, singleLongValue(WORKFLOW_COUNTER));
    assertEquals(1, singleLongValue(ACTIVITY_COUNTER));

    // Replay the full workflow history and verify workflow metrics are suppressed on replay
    WorkflowReplayer.replayWorkflowExecution(
        testWorkflowRule.getExecutionHistory(execution.getWorkflowId()), TestWorkflowImpl.class);

    assertEquals(1, singleLongValue(WORKFLOW_COUNTER));
    assertEquals(1, singleLongValue(ACTIVITY_COUNTER));
  }

  private static long singleLongValue(String name) {
    Collection<LongPointData> points = requireMetricNamed(name).getLongSumData().getPoints();
    assertEquals(name + " points: " + points, 1, points.size());
    return points.iterator().next().getValue();
  }
}
