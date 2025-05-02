package io.temporal.workflow;

import io.temporal.client.WorkflowException;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class WorkflowInitRetryTest {
  private static final Map<String, AtomicInteger> retryCount = new ConcurrentHashMap<>();

  @Rule public TestName testName = new TestName();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              WorkflowImplementationOptions.newBuilder()
                  .setFailWorkflowExceptionTypes(IllegalArgumentException.class)
                  .build(),
              TestInitWorkflow.class)
          .build();

  @Test
  public void testInitFailsRuntimeException() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    WorkflowException exception =
        Assert.assertThrows(
            WorkflowException.class, () -> workflowStub.execute(testName.getMethodName()));
    System.out.println(exception);
  }

  public static class TestInitWorkflow implements TestWorkflows.TestWorkflow1 {

    @WorkflowInit
    public TestInitWorkflow(String testName) {
      AtomicInteger count = retryCount.get(testName);
      if (count == null) {
        count = new AtomicInteger();
        retryCount.put(testName, count);
      }
      int c = count.incrementAndGet();
      if (c < 3) {
        throw new IllegalStateException("simulated " + c);
      } else {
        throw new IllegalArgumentException("simulated " + c);
      }
    }

    @Override
    public String execute(String taskQueue) {
      return taskQueue;
    }
  }
}
