package io.temporal.workflow.childWorkflowTests;

import static org.junit.Assert.*;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowFailedException;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.ChildWorkflowStub;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestWorkflows.ITestNamedChild;
import io.temporal.workflow.shared.TestWorkflows.TestNamedChild;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;

public class NamedChildTest {

  private static final String childReExecuteId = UUID.randomUUID().toString();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNamedChild.class, TestChildReExecuteWorkflow.class)
          .build();

  @Test
  public void testChildAlreadyRunning() {
    WorkflowIdReusePolicyParent client =
        testWorkflowRule.newWorkflowStub200sTimeoutOptions(WorkflowIdReusePolicyParent.class);
    try {
      client.execute(false, WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof ChildWorkflowFailure);
    }
  }

  @Test
  public void testChildStartTwice() {
    WorkflowIdReusePolicyParent client =
        testWorkflowRule.newWorkflowStub200sTimeoutOptions(WorkflowIdReusePolicyParent.class);
    try {
      client.execute(true, WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof ChildWorkflowFailure);
    }
  }

  @Test
  public void testChildReexecute() {
    WorkflowIdReusePolicyParent client =
        testWorkflowRule.newWorkflowStub200sTimeoutOptions(WorkflowIdReusePolicyParent.class);
    assertEquals(
        "HELLO WORLD!",
        client.execute(false, WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE));
  }

  @WorkflowInterface
  public interface WorkflowIdReusePolicyParent {

    @WorkflowMethod
    String execute(boolean parallel, WorkflowIdReusePolicy policy);
  }

  public static class TestChildReExecuteWorkflow implements WorkflowIdReusePolicyParent {

    public TestChildReExecuteWorkflow() {}

    @Override
    public String execute(boolean parallel, WorkflowIdReusePolicy policy) {
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder()
              .setWorkflowId(childReExecuteId)
              .setWorkflowIdReusePolicy(policy)
              .build();

      ITestNamedChild child1 = Workflow.newChildWorkflowStub(ITestNamedChild.class, options);
      Promise<String> r1P = Async.function(child1::execute, "Hello ");
      String r1 = null;
      if (!parallel) {
        r1 = r1P.get();
      }
      ITestNamedChild child2 = Workflow.newChildWorkflowStub(ITestNamedChild.class, options);
      ChildWorkflowStub child2Stub = ChildWorkflowStub.fromTyped(child2);
      // Same as String r2 = child2.execute("World!");
      String r2 = child2Stub.execute(String.class, "World!");
      if (parallel) {
        r1 = r1P.get();
      }
      assertEquals(childReExecuteId, Workflow.getWorkflowExecution(child1).get().getWorkflowId());
      assertEquals(childReExecuteId, Workflow.getWorkflowExecution(child2).get().getWorkflowId());
      return r1 + r2;
    }
  }
}
