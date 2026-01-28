package io.temporal.workflow;

import io.temporal.activity.ActivityInterface;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow3;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LargeHistoryTest {

  private static final Logger log = LoggerFactory.getLogger(LargeHistoryTest.class);
  private final TestLargeWorkflowActivityImpl activitiesImpl = new TestLargeWorkflowActivityImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestLargeHistory.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testLargeHistory() {
    // this test fails with external docker and needs much larger timeouts
    if (testWorkflowRule.isUseExternalService()) {
      return;
    }
    final int activityCount = 1000;
    TestWorkflow3 workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflow3.class,
                SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
                    .toBuilder()
                    .setWorkflowTaskTimeout(Duration.ofSeconds(30))
                    .build());
    long start = System.currentTimeMillis();
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue(), activityCount);
    long duration = System.currentTimeMillis() - start;
    log.info(testWorkflowRule.getTestEnvironment().getNamespace() + " duration is " + duration);
    Assert.assertEquals("done", result);
  }

  @ActivityInterface
  public interface TestLargeWorkflowActivity {
    String activity();
  }

  public static class TestLargeWorkflowActivityImpl implements TestLargeWorkflowActivity {
    @Override
    public String activity() {
      return "done";
    }
  }

  public static class TestLargeHistory implements TestWorkflow3 {

    @Override
    public String execute(String taskQueue, int activityCount) {
      TestLargeWorkflowActivity activities =
          Workflow.newActivityStub(
              TestLargeWorkflowActivity.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));
      List<Promise<String>> results = new ArrayList<>();
      for (int i = 0; i < activityCount; i++) {
        Promise<String> result = Async.function(activities::activity);
        results.add(result);
      }
      Promise.allOf(results).get();
      return "done";
    }
  }
}
