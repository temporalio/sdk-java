package io.temporal.workflow.updateTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;

public class UpdateValidatorFailureSerializationTest {

  private static final String FAILURE_MESSAGE = "validator rejected";
  private static final String FAILURE_TYPE = "TestValidatorFailure";
  private static final String FAILURE_DETAIL = "failure detail";

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflowImpl.class).build();

  @Test
  public void validatorPreservesApplicationFailure() {
    TestWorkflow workflow = startWorkflow();

    WorkflowUpdateException exception =
        assertThrows(WorkflowUpdateException.class, () -> workflow.update(true));

    assertApplicationFailure(exception);
    workflow.complete();
  }

  private TestWorkflow startWorkflow() {
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(UUID.randomUUID().toString())
            .build();
    TestWorkflow workflow =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflow.class, options);
    WorkflowClient.start(workflow::execute);
    SDKTestWorkflowRule.waitForOKQuery(workflow);
    return workflow;
  }

  private static void assertApplicationFailure(WorkflowUpdateException exception) {
    assertNotNull(exception.getCause());
    assertTrue(exception.getCause() instanceof ApplicationFailure);

    ApplicationFailure failure = (ApplicationFailure) exception.getCause();
    assertEquals(FAILURE_MESSAGE, failure.getOriginalMessage());
    assertEquals(FAILURE_TYPE, failure.getType());
    assertTrue(failure.isNonRetryable());
    assertEquals(1, failure.getDetails().getSize());
    assertEquals(FAILURE_DETAIL, failure.getDetails().get(0, String.class));
    assertNull(failure.getCause());
  }

  @WorkflowInterface
  public interface TestWorkflow {

    @WorkflowMethod
    void execute();

    @QueryMethod
    String getState();

    @UpdateMethod
    void update(boolean reject);

    @UpdateValidatorMethod(updateName = "update")
    void validateUpdate(boolean reject);

    @UpdateMethod
    void complete();
  }

  public static class TestWorkflowImpl implements TestWorkflow {

    private final CompletablePromise<Void> done = Workflow.newPromise();

    @Override
    public void execute() {
      done.get();
    }

    @Override
    public String getState() {
      return "ready";
    }

    @Override
    public void update(boolean reject) {}

    @Override
    public void validateUpdate(boolean reject) {
      if (reject) {
        throw ApplicationFailure.newNonRetryableFailure(
            FAILURE_MESSAGE, FAILURE_TYPE, FAILURE_DETAIL);
      }
    }

    @Override
    public void complete() {
      done.complete(null);
    }
  }
}
