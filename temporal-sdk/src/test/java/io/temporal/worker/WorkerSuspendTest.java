package io.temporal.worker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.Rule;
import org.junit.Test;

public class WorkerSuspendTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflowImpl.class).build();

  @Test
  public void testSuspendResume() {
    Worker worker = testWorkflowRule.getWorker();
    assertFalse(worker.isSuspended());
    worker.suspendPolling();
    assertTrue(worker.isSuspended());
    worker.resumePolling();
    assertFalse(worker.isSuspended());
  }

  public static class TestWorkflowImpl implements TestWorkflow1 {
    @Override
    public String execute(String now) {
      return "";
    }
  }
}
