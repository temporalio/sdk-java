package io.temporal.workflow.failure;

import static io.temporal.testUtils.Eventually.assertEventually;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class WorkflowFailureGetVersionTest {

  @Rule public TestName testName = new TestName();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowGetVersionAndException.class)
          .build();

  @Test
  public void getVersionAndException() {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute, testName.getMethodName());
    WorkflowStub workflowStub = WorkflowStub.fromTyped(workflow);

    try {
      HistoryEvent workflowTaskFailed =
          assertEventually(
              Duration.ofSeconds(5),
              () -> {
                List<HistoryEvent> failedEvents =
                    testWorkflowRule.getHistoryEvents(
                        execution.getWorkflowId(), EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED);
                Assert.assertFalse("No workflow task failure recorded", failedEvents.isEmpty());
                return failedEvents.get(0);
              });

      Failure failure =
          getDeepestFailure(workflowTaskFailed.getWorkflowTaskFailedEventAttributes().getFailure());
      Assert.assertEquals("Any error", failure.getMessage());
      Assert.assertTrue(failure.hasApplicationFailureInfo());
      Assert.assertEquals(
          RuntimeException.class.getName(), failure.getApplicationFailureInfo().getType());
    } finally {
      try {
        workflowStub.terminate("terminate test workflow");
      } catch (WorkflowException ignored) {
      }
    }
  }

  private static Failure getDeepestFailure(Failure failure) {
    while (failure.hasCause()) {
      failure = failure.getCause();
    }
    return failure;
  }

  public static class TestWorkflowGetVersionAndException implements TestWorkflow1 {

    @Override
    public String execute(String unused) {
      String changeId = "change-id";
      Workflow.getVersion(changeId, Workflow.DEFAULT_VERSION, 1);
      Workflow.getVersion(changeId, Workflow.DEFAULT_VERSION, 1);
      throw new RuntimeException("Any error");
    }
  }
}
