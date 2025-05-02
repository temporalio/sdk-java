package io.temporal.workflow;

import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class WorkflowTaskTimeoutWorkflowTest {

  private static final Map<String, AtomicInteger> retryCount = new ConcurrentHashMap<>();

  @Rule public TestName testName = new TestName();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(WorkflowTaskTimeoutWorkflowImpl.class)
          .build();

  @Test
  public void testWorkflowTaskTimeoutWorkflow() throws InterruptedException {

    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .build();

    WorkflowTaskTimeoutWorkflow stub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(WorkflowTaskTimeoutWorkflow.class, options);
    String result = stub.execute(testName.getMethodName());
    Assert.assertEquals("some result", result);
  }

  @WorkflowInterface
  public interface WorkflowTaskTimeoutWorkflow {
    @WorkflowMethod
    String execute(String testName) throws InterruptedException;
  }

  public static class WorkflowTaskTimeoutWorkflowImpl implements WorkflowTaskTimeoutWorkflow {

    @Override
    public String execute(String testName) throws InterruptedException {

      AtomicInteger count = retryCount.get(testName);
      if (count == null) {
        count = new AtomicInteger();
        retryCount.put(testName, count);
        Thread.sleep(2000);
      }

      return "some result";
    }
  }
}
