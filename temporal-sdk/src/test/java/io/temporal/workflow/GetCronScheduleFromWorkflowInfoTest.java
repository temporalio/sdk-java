package io.temporal.workflow;

import static io.temporal.testing.internal.SDKTestOptions.newWorkflowOptionsWithTimeouts;
import static org.junit.Assert.*;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class GetCronScheduleFromWorkflowInfoTest {

  @Rule public TestName testName = new TestName();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestGetCronScheduleWorkflowsFuncImpl.class)
          .build();

  @Test
  public void testGetCronScheduleFromWorkflowInfo() throws InterruptedException {
    Assume.assumeFalse("skipping for docker tests", testWorkflowRule.isUseExternalService());

    WorkflowStub workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(
                "TestGetCronScheduleWorkflowsFunc",
                newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
                    .setCronSchedule("0 0 * * *")
                    .build());
    testWorkflowRule.registerDelayedCallback(Duration.ofDays(1), workflowStub::cancel);
    workflowStub.start(testName.getMethodName());

    // make sure that the cron workflow was cancelled
    try {
      workflowStub.getResult(String.class);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }

    // make sure we have the completion results available
    Map<Integer, String> lastCompletionResults =
        TestGetCronScheduleWorkflowsFuncImpl.lastCompletionResults.get(testName.getMethodName());
    assertNotNull(lastCompletionResults);
    assertTrue(lastCompletionResults.size() > 0);
    // get the very last run completion result and make sure its the cron
    assertEquals("0 0 * * *", lastCompletionResults.get(lastCompletionResults.size()));
  }

  @WorkflowInterface
  public interface TestGetCronScheduleWorkflowsFunc {
    @WorkflowMethod
    String func(String testName);
  }

  public static class TestGetCronScheduleWorkflowsFuncImpl
      implements TestGetCronScheduleWorkflowsFunc {

    public static final Map<String, Map<Integer, String>> lastCompletionResults =
        new ConcurrentHashMap<>();
    public static final Map<String, AtomicInteger> retryCount = new ConcurrentHashMap<>();

    @Override
    public String func(String testName) {
      int count = retryCount.computeIfAbsent(testName, k -> new AtomicInteger()).incrementAndGet();
      lastCompletionResults
          .computeIfAbsent(testName, k -> new HashMap<>())
          .put(count, Workflow.getLastCompletionResult(String.class));

      return Workflow.getInfo().getCronSchedule();
    }
  }
}
