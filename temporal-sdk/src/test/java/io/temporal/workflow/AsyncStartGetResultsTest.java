package io.temporal.workflow;

import static junit.framework.TestCase.*;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowServiceException;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class AsyncStartGetResultsTest {
  @Rule public TestName testName = new TestName();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflowImpl.class).build();

  @Test(expected = TimeoutException.class)
  public void testGetResults() {
    TestWorkflow workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflow.class,
                SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
                    .toBuilder()
                    .build());

    WorkflowClient.start(workflow::execute, "Hello");

    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    try {
      String result = untyped.getResult(3, TimeUnit.SECONDS, String.class);
      assertNotNull(result);
    } catch (Exception e) {
      assertTrue(e instanceof WorkflowServiceException);
      assertFalse(e instanceof TimeoutException); // this really should be TimeoutException
    }
  }

  @Test
  public void testGetResultAsync() {
    TestWorkflow workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflow.class,
                SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
                    .toBuilder()
                    .build());

    WorkflowClient.start(workflow::execute, "Hello");

    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    CompletableFuture<String> future = untyped.getResultAsync(3, TimeUnit.SECONDS, String.class);
    try {
      String result = future.get();
      assertNotNull(result);
    } catch (Exception e) {
      assertTrue(e instanceof ExecutionException);
      assertFalse(e instanceof TimeoutException); // this really should be TimeoutException
    }
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String execute(String arg);
  }

  public class TestWorkflowImpl implements TestWorkflow {
    @Override
    public String execute(String arg) {
      // just sleep
      Workflow.sleep(Duration.ofSeconds(30));
      return "done";
    }
  }
}
