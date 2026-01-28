package io.temporal.workflow;

import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.Version;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WritesSDKNameVersionTest {

  private static boolean hasFailedWFT = false;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowTaskFailureBackoff.class)
          .build();

  @Test
  public void writesSdkNameAndVersion() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(10))
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();

    TestWorkflow1 workflowStub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflow1.class, options);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("result1", result);

    List<HistoryEvent> completedEvents =
        testWorkflowRule.getHistoryEvents(
            WorkflowStub.fromTyped(workflowStub).getExecution().getWorkflowId(),
            EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED);
    Assert.assertEquals(
        Version.SDK_NAME,
        completedEvents
            .get(0)
            .getWorkflowTaskCompletedEventAttributes()
            .getSdkMetadata()
            .getSdkName());
    Assert.assertEquals(
        Version.LIBRARY_VERSION,
        completedEvents
            .get(0)
            .getWorkflowTaskCompletedEventAttributes()
            .getSdkMetadata()
            .getSdkVersion());
    Assert.assertEquals(
        "",
        completedEvents
            .get(1)
            .getWorkflowTaskCompletedEventAttributes()
            .getSdkMetadata()
            .getSdkName());
    Assert.assertEquals(
        "",
        completedEvents
            .get(1)
            .getWorkflowTaskCompletedEventAttributes()
            .getSdkMetadata()
            .getSdkVersion());
  }

  public static class TestWorkflowTaskFailureBackoff implements TestWorkflow1 {
    @Override
    public String execute(String taskQueue) {
      // Complete one wft first
      Workflow.sleep(1);
      if (!hasFailedWFT) {
        hasFailedWFT = true;
        throw new Error("fail");
      }
      return "result1";
    }
  }
}
