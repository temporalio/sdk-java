package io.temporal.workflow;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.QueryableWorkflow;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class NoQueryThreadLeakTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestNoQueryWorkflowImpl.class).build();

  @Test
  public void testNoQueryThreadLeak() throws InterruptedException {
    QueryableWorkflow client =
        testWorkflowRule.newWorkflowStubTimeoutOptions(QueryableWorkflow.class);
    WorkflowClient.start(client::execute);
    testWorkflowRule.sleep(Duration.ofSeconds(1));
    int threadCount = ManagementFactory.getThreadMXBean().getThreadCount();

    // Calls query multiple times to check at the end of the method that if it doesn't leak threads
    int queryCount = 100;
    for (int i = 0; i < queryCount; i++) {
      Assert.assertEquals("some state", client.getState());
      if (testWorkflowRule.isUseExternalService()) {
        // Sleep a little bit to avoid server throttling error.
        Thread.sleep(50);
      }
    }
    client.mySignal("Hello ");
    WorkflowStub.fromTyped(client).getResult(String.class);
    // Ensures that no threads were leaked due to query
    int threadsCreated = ManagementFactory.getThreadMXBean().getThreadCount() - threadCount;
    Assert.assertTrue("query leaks threads: " + threadsCreated, threadsCreated < queryCount);
  }

  public static class TestNoQueryWorkflowImpl implements QueryableWorkflow {

    CompletablePromise<Void> promise = Workflow.newPromise();

    @Override
    public String execute() {
      promise.get();
      return "done";
    }

    @Override
    public String getState() {
      return "some state";
    }

    @Override
    public void mySignal(String value) {
      promise.complete(null);
    }
  }
}
