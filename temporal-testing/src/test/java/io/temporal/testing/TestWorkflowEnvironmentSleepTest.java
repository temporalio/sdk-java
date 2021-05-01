package io.temporal.testing;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.worker.Worker;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestWorkflowEnvironmentSleepTest {

  @WorkflowInterface
  public interface ExampleWorkflow {
    @WorkflowMethod
    void execute();

    @SignalMethod
    void updateState(String state);
  }

  public static class ExampleWorkflowImpl implements ExampleWorkflow {
    @Override
    public void execute() {
      Workflow.sleep(Duration.ofMinutes(20));
    }

    @Override
    public void updateState(String newState) {}
  }

  private TestWorkflowEnvironment testEnv;
  private Worker worker;
  private WorkflowClient client;
  private ExampleWorkflow workflow;
  private static final String WORKFLOW_TASK_QUEUE = "EXAMPLE";

  @Before
  public void setUp() {
    testEnv = TestWorkflowEnvironment.newInstance();
    worker = testEnv.newWorker(WORKFLOW_TASK_QUEUE);
    client = testEnv.getWorkflowClient();
    worker.registerWorkflowImplementationTypes(ExampleWorkflowImpl.class);
    workflow =
        client.newWorkflowStub(
            ExampleWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(WORKFLOW_TASK_QUEUE).build());
    testEnv.start();
  }

  @Test(timeout = 2000)
  public void testCompletedThenTimerCompletes() throws InterruptedException {
    WorkflowClient.start(workflow::execute);
    // commenting out signal makes the test pass
    workflow.updateState("PRELIM_COMPLETED");
    testEnv.sleep(Duration.ofMinutes(50L));
  }

  @After
  public void tearDown() {
    testEnv.close();
  }
}
