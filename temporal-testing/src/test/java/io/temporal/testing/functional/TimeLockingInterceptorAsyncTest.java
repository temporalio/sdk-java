package io.temporal.testing.functional;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import org.junit.Rule;
import org.junit.Test;

public class TimeLockingInterceptorAsyncTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(HangingWorkflowImpl.class).build();

  /**
   * Verifies that the first getResultAsync doesn't hold timeskipping lock and the second
   * getResultAsync returns result fast too
   */
  @Test
  public void testAsyncGetResultDoesntRetainTimeLock()
      throws ExecutionException, InterruptedException {
    HangingWorkflow typedStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(HangingWorkflow.class);
    WorkflowStub untypedStub = WorkflowStub.fromTyped(typedStub);
    untypedStub.start();
    assertEquals("done", untypedStub.getResultAsync(String.class).get());

    typedStub = testWorkflowRule.newWorkflowStubTimeoutOptions(HangingWorkflow.class);
    untypedStub = WorkflowStub.fromTyped(typedStub);
    untypedStub.start();
    assertEquals("done", untypedStub.getResultAsync(String.class).get());
  }

  @WorkflowInterface
  public interface HangingWorkflow {
    @WorkflowMethod
    String execute();
  }

  public static class HangingWorkflowImpl implements HangingWorkflow {
    @Override
    public String execute() {
      Workflow.sleep(Duration.ofSeconds(150));
      return "done";
    }
  }
}
