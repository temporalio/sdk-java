package io.temporal.workflow;

import static io.temporal.testing.internal.SDKTestWorkflowRule.NAMESPACE;

import com.google.common.collect.ImmutableMap;
import com.uber.m3.tally.RootScopeBuilder;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkerMetricsTag;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.Map;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowTaskFailureBackoffTest {

  private static int testWorkflowTaskFailureBackoffReplayCount;

  private final TestStatsReporter reporter = new TestStatsReporter();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowTaskFailureBackoff.class)
          .setMetricsScope(
              new RootScopeBuilder()
                  .reporter(reporter)
                  .reportEvery(com.uber.m3.util.Duration.ofMillis(10)))
          .build();

  @Test(timeout = 15_000)
  public void testWorkflowTaskFailureBackoff() {
    testWorkflowTaskFailureBackoffReplayCount = 0;
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(10))
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();

    TestWorkflow1 workflowStub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflow1.class, options);
    long start = testWorkflowRule.getTestEnvironment().currentTimeMillis();
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    long elapsed = testWorkflowRule.getTestEnvironment().currentTimeMillis() - start;
    Assert.assertTrue("spinned on fail workflow task", elapsed > 1000);
    Assert.assertEquals("result1", result);
    WorkflowExecution execution = WorkflowStub.fromTyped(workflowStub).getExecution();

    Assert.assertEquals(
        1,
        testWorkflowRule
            .getHistoryEvents(execution.getWorkflowId(), EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED)
            .size());
    Map<String, String> tags =
        ImmutableMap.<String, String>builder()
            .putAll(MetricsTag.defaultTags(NAMESPACE))
            .put(MetricsTag.WORKER_TYPE, WorkerMetricsTag.WorkerType.WORKFLOW_WORKER.getValue())
            .put(MetricsTag.TASK_QUEUE, testWorkflowRule.getTaskQueue())
            .put(MetricsTag.WORKFLOW_TYPE, "TestWorkflow1")
            .buildKeepingLast();
    reporter.assertCounter(MetricsType.WORKFLOW_TASK_NO_COMPLETION_COUNTER, tags, 1);
  }

  public static class TestWorkflowTaskFailureBackoff implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      if (testWorkflowTaskFailureBackoffReplayCount++ < 2) {
        throw new Error("simulated workflow task failure");
      }
      return "result1";
    }
  }
}
