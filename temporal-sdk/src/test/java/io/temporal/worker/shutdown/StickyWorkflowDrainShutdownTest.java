package io.temporal.worker.shutdown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

public class StickyWorkflowDrainShutdownTest {
  private static final Duration DRAIN_TIME = Duration.ofSeconds(7);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setUseTimeskipping(false)
          .setWorkerOptions(
              WorkerOptions.newBuilder().setStickyTaskQueueDrainTimeout(DRAIN_TIME).build())
          .setWorkflowServiceStubsOptions(
              WorkflowServiceStubsOptions.newBuilder()
                  .setRpcLongPollTimeout(Duration.ofSeconds(5))
                  .build())
          .build();

  @Test
  public void testShutdown() {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStub(TestWorkflow1.class);
    WorkflowClient.start(workflow::execute, null);
    testWorkflowRule.getTestEnvironment().shutdown();
    long startTime = System.currentTimeMillis();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);
    long endTime = System.currentTimeMillis();
    assertTrue("Drain time should be respected", endTime - startTime > DRAIN_TIME.toMillis());
    assertTrue(testWorkflowRule.getTestEnvironment().getWorkerFactory().isTerminated());
    // Workflow should complete successfully since the drain time is longer than the workflow
    // execution time
    assertEquals("Success", workflow.execute(null));
  }

  @Test
  public void testShutdownNow() {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStub(TestWorkflow1.class);
    WorkflowClient.start(workflow::execute, null);
    long startTime = System.currentTimeMillis();
    testWorkflowRule.getTestEnvironment().shutdownNow();
    long endTime = System.currentTimeMillis();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);
    assertTrue(
        "Drain time does not need to be respected", endTime - startTime < DRAIN_TIME.toMillis());
    assertTrue(testWorkflowRule.getTestEnvironment().getWorkerFactory().isTerminated());
    // Cleanup workflow that will not finish
    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    untyped.terminate("terminate");
  }

  public static class TestWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String now) {
      for (int i = 0; i < 5; i++) {
        Workflow.sleep(1000);
      }
      return "Success";
    }
  }
}
