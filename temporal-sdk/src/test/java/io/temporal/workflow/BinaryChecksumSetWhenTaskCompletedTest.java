package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.Rule;
import org.junit.Test;

public class BinaryChecksumSetWhenTaskCompletedTest {
  private static final String BINARY_CHECKSUM = "testChecksum";

  @SuppressWarnings("deprecation")
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowClientOptions(WorkflowClientOptions.newBuilder().build())
          .setWorkerOptions(WorkerOptions.newBuilder().setBuildId(BINARY_CHECKSUM).build())
          .setWorkflowTypes(SimpleTestWorkflow.class)
          .build();

  @Test
  @SuppressWarnings("deprecation")
  public void testBinaryChecksumSetWhenTaskCompleted() {
    TestWorkflow1 client = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowExecution execution =
        WorkflowClient.start(client::execute, testWorkflowRule.getTaskQueue());
    WorkflowStub stub = WorkflowStub.fromTyped(client);
    SDKTestWorkflowRule.waitForOKQuery(stub);

    HistoryEvent completionEvent =
        testWorkflowRule.getHistoryEvent(
            execution.getWorkflowId(), EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED);
    assertNotNull(completionEvent);
    // The build id needs to either show up in the old binary checksum, if server is older,
    // or the worker versioning stamp.
    String inBinaryChecksum =
        completionEvent.getWorkflowTaskCompletedEventAttributes().getBinaryChecksum();
    if (!inBinaryChecksum.isEmpty()) {
      assertEquals(BINARY_CHECKSUM, inBinaryChecksum);
    } else {
      assertEquals(
          BINARY_CHECKSUM,
          completionEvent
              .getWorkflowTaskCompletedEventAttributes()
              .getWorkerVersion()
              .getBuildId());
    }
  }

  public static class SimpleTestWorkflow implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      VariousTestActivities testActivities =
          Workflow.newActivityStub(
              VariousTestActivities.class,
              ActivityOptions.newBuilder(SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue))
                  .build());
      testActivities.activity();
      return "done";
    }
  }
}
