package com.uber.cadence.workflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.testing.TestEnvironmentOptions;
import com.uber.cadence.testing.TestEnvironmentOptions.Builder;
import com.uber.cadence.testing.TestWorkflowEnvironment;
import com.uber.cadence.worker.Worker;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.StatsReporter;
import java.time.Duration;
import org.junit.Test;

public class MetricsTest {
  private static final String taskList = "metrics-test";

  public interface TestWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class TestMetricsInWorkflow implements TestWorkflow {
    @Override
    public void execute() {
      Workflow.getMetricsScope().counter("test-started").inc(1);

      ChildWorkflowOptions options =
          new ChildWorkflowOptions.Builder().setTaskList(taskList).build();
      TestChildWorkflow workflow = Workflow.newChildWorkflowStub(TestChildWorkflow.class, options);
      workflow.executeChild();

      Workflow.getMetricsScope().counter("test-done").inc(1);
    }
  }

  public interface TestChildWorkflow {
    @WorkflowMethod
    void executeChild();
  }

  public static class TestMetricsInChildWorkflow implements TestChildWorkflow {

    @Override
    public void executeChild() {
      Workflow.getMetricsScope().counter("test-child-started").inc(1);
      Workflow.getMetricsScope().counter("test-child-done").inc(1);
    }
  }

  @Test
  public void testWorkflowMetrics() throws InterruptedException {
    StatsReporter reporter = mock(StatsReporter.class);
    Scope scope =
        new RootScopeBuilder()
            .reporter(reporter)
            .reportEvery(com.uber.m3.util.Duration.ofMillis(10));

    TestEnvironmentOptions testOptions =
        new Builder().setDomain(WorkflowTest.DOMAIN).setMetricsScope(scope).build();
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance(testOptions);
    Worker worker = env.newWorker(taskList);
    worker.registerWorkflowImplementationTypes(
        TestMetricsInWorkflow.class, TestMetricsInChildWorkflow.class);
    worker.start();

    WorkflowClient workflowClient = env.newWorkflowClient();
    WorkflowOptions options =
        new WorkflowOptions.Builder()
            .setExecutionStartToCloseTimeout(Duration.ofSeconds(1000))
            .setTaskList(taskList)
            .build();
    TestWorkflow workflow = workflowClient.newWorkflowStub(TestWorkflow.class, options);
    workflow.execute();

    Thread.sleep(20);

    verify(reporter, times(1)).reportCounter("test-started", null, 1);
    verify(reporter, times(1)).reportCounter("test-done", null, 1);
    verify(reporter, times(1)).reportCounter("test-child-started", null, 1);
    verify(reporter, times(1)).reportCounter("test-child-done", null, 1);
  }
}
