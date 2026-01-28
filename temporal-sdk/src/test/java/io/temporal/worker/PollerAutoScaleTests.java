package io.temporal.worker;

import static org.junit.Assume.assumeTrue;

import io.temporal.client.WorkflowClient;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.tuning.PollerBehaviorAutoscaling;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

public class PollerAutoScaleTests {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setWorkflowTaskPollersBehavior(new PollerBehaviorAutoscaling(1, 10, 5))
                  .setActivityTaskPollersBehavior(new PollerBehaviorAutoscaling(1, 10, 5))
                  .build())
          .setActivityImplementations(new ResourceBasedTunerTests.ActivitiesImpl())
          .setWorkflowTypes(ResourceBasedTunerTests.ResourceTunerWorkflowImpl.class)
          .build();

  @Before
  public void checkRealServer() {
    assumeTrue(
        "Test Server doesn't support poller autoscaling", SDKTestWorkflowRule.useExternalService);
  }

  @Category(IndependentResourceBasedTests.class)
  @Test(timeout = 300 * 1000)
  public void canRunHeavyLoadWithPollerAutoScaling() {
    List<CompletableFuture<String>> workflowResults = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      ResourceBasedTunerTests.ResourceTunerWorkflow workflow =
          testWorkflowRule.newWorkflowStub(ResourceBasedTunerTests.ResourceTunerWorkflow.class);
      workflowResults.add(WorkflowClient.execute(workflow::execute, 50, 0, 100));
    }

    for (CompletableFuture<String> workflowResult : workflowResults) {
      String result = workflowResult.join();
      System.out.println(result);
    }
  }
}
