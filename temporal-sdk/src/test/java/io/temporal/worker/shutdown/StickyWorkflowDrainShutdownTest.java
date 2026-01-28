package io.temporal.worker.shutdown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.tuning.PollerBehavior;
import io.temporal.worker.tuning.PollerBehaviorAutoscaling;
import io.temporal.worker.tuning.PollerBehaviorSimpleMaximum;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class StickyWorkflowDrainShutdownTest {
  private static final Duration DRAIN_TIME = Duration.ofSeconds(7);

  @Parameterized.Parameters
  public static Collection<PollerBehavior> data() {
    return Arrays.asList(
        new PollerBehaviorSimpleMaximum(10), new PollerBehaviorAutoscaling(1, 10, 10));
  }

  @Rule public SDKTestWorkflowRule testWorkflowRule;

  public StickyWorkflowDrainShutdownTest(PollerBehavior pollerBehaviorAutoscaling) {
    this.testWorkflowRule =
        SDKTestWorkflowRule.newBuilder()
            .setWorkflowTypes(TestWorkflowImpl.class)
            .setUseTimeskipping(false)
            .setWorkerOptions(
                WorkerOptions.newBuilder()
                    .setWorkflowTaskPollersBehavior(pollerBehaviorAutoscaling)
                    .setStickyTaskQueueDrainTimeout(DRAIN_TIME)
                    .build())
            .setWorkflowServiceStubsOptions(
                WorkflowServiceStubsOptions.newBuilder()
                    .setRpcLongPollTimeout(Duration.ofSeconds(5))
                    .build())
            .build();
  }

  @Test
  public void testShutdown() throws InterruptedException {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStub(TestWorkflow1.class);
    WorkflowClient.start(workflow::execute, null);
    testWorkflowRule.getTestEnvironment().shutdown();
    long startTime = System.currentTimeMillis();
    System.out.println("Waiting for shutdown to complete");
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);
    assertTrue(testWorkflowRule.getTestEnvironment().getWorkerFactory().isTerminated());
    System.out.println("Shutdown completed");
    long endTime = System.currentTimeMillis();
    assertTrue("Drain time should be respected", endTime - startTime > DRAIN_TIME.toMillis());
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
