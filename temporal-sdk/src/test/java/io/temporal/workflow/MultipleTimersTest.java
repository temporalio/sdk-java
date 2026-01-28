package io.temporal.workflow;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class MultipleTimersTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestMultipleTimersImpl.class).build();

  @Test
  public void testMultipleTimers() {
    TestMultipleTimers workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestMultipleTimers.class);
    long result = workflowStub.execute();
    Assert.assertTrue("should be around 1 second: " + result, result < 2000);
  }

  @WorkflowInterface
  public interface TestMultipleTimers {
    @WorkflowMethod
    long execute();
  }

  public static class TestMultipleTimersImpl implements TestMultipleTimers {

    @Override
    public long execute() {
      Promise<Void> t1 = Async.procedure(() -> Workflow.sleep(Duration.ofSeconds(1)));
      Promise<Void> t2 = Async.procedure(() -> Workflow.sleep(Duration.ofSeconds(2)));
      long start = Workflow.currentTimeMillis();
      Promise.anyOf(t1, t2).get();
      long elapsed = Workflow.currentTimeMillis() - start;
      return elapsed;
    }
  }
}
