package io.temporal.testing;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

// Some test server calls make take a long time. For example, when sleep leads to triggering a lot
// of events.
// Our regular rpcTimeout is 10 seconds.
// We need to make sure that such sleeps don't throw.
// This is achieved by test service stubs initialized with rpc timeout of Long.MAX_VALUE
public class TestEnvToleratesLongTestServerCalls {
  private TestWorkflowEnvironment testEnv;
  private WorkflowClient client;
  private static final String WORKFLOW_TASK_QUEUE = "EXAMPLE";

  @Before
  public void setUp() {
    setUp(TestEnvironmentOptions.getDefaultInstance());
  }

  private void setUp(TestEnvironmentOptions options) {
    testEnv = TestWorkflowEnvironment.newInstance(options);
    Worker worker = testEnv.newWorker(WORKFLOW_TASK_QUEUE);
    client = testEnv.getWorkflowClient();
    worker.registerWorkflowImplementationTypes(HangingWorkflowImpl.class);
    testEnv.start();
  }

  @After
  public void tearDown() {
    testEnv.close();
  }

  @Test(timeout = 20_000)
  public void sleepLongerThanRpcTimeoutDoesntThrow() {
    HangingWorkflow workflow =
        client.newWorkflowStub(
            HangingWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(WORKFLOW_TASK_QUEUE).build());
    WorkflowClient.start(workflow::execute);
    testEnv.registerDelayedCallback(
        Duration.ofMinutes(5),
        () -> {
          try {
            // 11 seconds is more than our standard rpcTimeout of 10 seconds which may cause
            Thread.sleep(11_000);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        });
    // This sleep takes longer than our standard rpcTimeout of 10 seconds.
    testEnv.sleep(Duration.ofMinutes(50L));
  }

  @WorkflowInterface
  public interface HangingWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class HangingWorkflowImpl implements HangingWorkflow {
    @Override
    public void execute() {
      Workflow.sleep(Duration.ofMinutes(20));
    }
  }
}
