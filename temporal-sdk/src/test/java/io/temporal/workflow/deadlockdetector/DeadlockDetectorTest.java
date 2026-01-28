package io.temporal.workflow.deadlockdetector;

import static org.junit.Assert.*;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.internal.common.env.DebugModeUtils;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowLongArg;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(value = Parameterized.class)
public class DeadlockDetectorTest {

  private final boolean debugMode;

  public DeadlockDetectorTest(boolean debugMode) {
    this.debugMode = debugMode;
  }

  private final WorkflowImplementationOptions workflowImplementationOptions =
      WorkflowImplementationOptions.newBuilder()
          .setFailWorkflowExceptionTypes(Throwable.class)
          .build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(workflowImplementationOptions, TestDeadlockWorkflow.class)
          .build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRuleWithDDDTimeout =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(workflowImplementationOptions, TestDeadlockWorkflow.class)
          .setWorkerOptions(
              WorkerOptions.newBuilder().setDefaultDeadlockDetectionTimeout(500).build())
          .build();

  @Before
  public void setUp() throws Exception {
    DebugModeUtils.override(debugMode);
  }

  @After
  public void tearDown() throws Exception {
    DebugModeUtils.reset();
  }

  @Test
  public void testDefaultDeadlockDetector() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    TestWorkflowLongArg workflow =
        workflowClient.newWorkflowStub(
            TestWorkflowLongArg.class,
            WorkflowOptions.newBuilder()
                .setWorkflowRunTimeout(Duration.ofSeconds(20))
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .build());
    if (DebugModeUtils.isTemporalDebugModeOn()) {
      workflow.execute(2000);
    } else {
      Throwable failure = assertThrows(WorkflowFailedException.class, () -> workflow.execute(2000));
      while (failure.getCause() != null) {
        failure = failure.getCause();
      }
      assertTrue(failure.getMessage().contains("Potential deadlock detected"));
      assertTrue(failure.getMessage().contains("Workflow.await"));
    }
  }

  @Test
  public void testSetDeadlockDetector() {
    WorkflowClient workflowClient = testWorkflowRuleWithDDDTimeout.getWorkflowClient();
    TestWorkflowLongArg workflow =
        workflowClient.newWorkflowStub(
            TestWorkflowLongArg.class,
            WorkflowOptions.newBuilder()
                .setWorkflowRunTimeout(Duration.ofSeconds(20))
                .setTaskQueue(testWorkflowRuleWithDDDTimeout.getTaskQueue())
                .build());
    if (DebugModeUtils.isTemporalDebugModeOn()) {
      workflow.execute(750);
    } else {
      Throwable failure = assertThrows(WorkflowFailedException.class, () -> workflow.execute(750));
      while (failure.getCause() != null) {
        failure = failure.getCause();
      }
      assertTrue(failure.getMessage().contains("Potential deadlock detected"));
      assertTrue(failure.getMessage().contains("Workflow.await"));
    }
  }

  public static class TestDeadlockWorkflow implements TestWorkflowLongArg {
    @Override
    public void execute(long millis) {
      Async.procedure(() -> Workflow.await(() -> false));
      Workflow.sleep(Duration.ofSeconds(1));
      try {
        Thread.sleep(millis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw Workflow.wrap(e);
      }
    }
  }

  @Parameterized.Parameters
  public static Collection<Boolean> debugModeParams() {
    return Arrays.asList(false, true);
  }
}
