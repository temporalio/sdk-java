package io.temporal.workflow.updateTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import io.temporal.activity.*;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.*;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.Optional;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateBadValidator {

  private static final Logger log = LoggerFactory.getLogger(UpdateTest.class);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(WorkerOptions.newBuilder().build())
          .setWorkflowTypes(TestUpdateWithBadValidatorWorkflowImpl.class)
          .build();

  @Test
  public void testBadUpdateValidator() {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    // To execute workflow client.execute() would do. But we want to start workflow and immediately
    // return.
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    SDKTestWorkflowRule.waitForOKQuery(workflow);
    assertEquals("initial", workflow.getState());

    assertEquals(workflowId, execution.getWorkflowId());

    for (String testCase : TestWorkflows.illegalCallCases) {
      assertThrows(WorkflowUpdateException.class, () -> workflow.update(0, testCase));
    }

    workflow.complete();

    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(execution, Optional.empty())
            .getResult(String.class);
    assertEquals("", result);
  }

  public static class TestUpdateWithBadValidatorWorkflowImpl
      implements TestWorkflows.WorkflowWithUpdate {
    String state = "initial";
    CompletablePromise<Void> promise = Workflow.newPromise();

    @Override
    public String execute() {
      promise.get();
      return "";
    }

    @Override
    public String getState() {
      return state;
    }

    @Override
    public String update(Integer index, String value) {
      return "";
    }

    @Override
    public void updateValidator(Integer index, String testCase) {
      TestWorkflows.illegalCalls(testCase);
    }

    @Override
    public void complete() {
      promise.complete(null);
    }

    @Override
    public void completeValidator() {}
  }

  @ActivityInterface
  public interface GreetingActivities {
    @ActivityMethod
    String hello(String input);
  }

  public static class ActivityImpl implements TestActivities.TestActivity1 {
    @Override
    public String execute(String input) {
      return Activity.getExecutionContext().getInfo().getActivityType() + "-" + input;
    }
  }
}
