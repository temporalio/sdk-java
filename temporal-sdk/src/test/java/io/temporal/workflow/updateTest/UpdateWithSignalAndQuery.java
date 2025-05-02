package io.temporal.workflow.updateTest;

import static org.junit.Assert.*;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateWithSignalAndQuery {
  private static final Logger log = LoggerFactory.getLogger(UpdateTest.class);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(WorkerOptions.newBuilder().build())
          .setWorkflowTypes(UpdateWithSignalAndQuery.TestUpdateWithSignalWorkflowImpl.class)
          .setActivityImplementations(new UpdateTest.ActivityImpl())
          .build();

  @Test
  public void testUpdateWithSignal() {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    TestWorkflows.WorkflowWithUpdateAndSignal workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdateAndSignal.class, options);
    // To execute workflow client.execute() would do. But we want to start workflow and immediately
    // return.
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    assertEquals(workflowId, execution.getWorkflowId());

    SDKTestWorkflowRule.waitForOKQuery(workflow);
    assertEquals("initial", workflow.getState());

    assertEquals("update 1", workflow.update("update 1"));
    workflow.signal("signal 1");
    assertEquals("update 2", workflow.update("update 2"));

    testWorkflowRule.waitForTheEndOfWFT(execution.getWorkflowId());
    testWorkflowRule.invalidateWorkflowCache();

    assertEquals("update 3", workflow.update("update 3"));

    testWorkflowRule.waitForTheEndOfWFT(execution.getWorkflowId());
    testWorkflowRule.invalidateWorkflowCache();

    workflow.signal("signal 2");

    workflow.complete();

    assertEquals(
        Arrays.asList("update 1", "signal 1", "update 2", "update 3", "signal 2"),
        workflow.execute());
  }

  @Test
  public void testSpeculativeUpdateWithSignal() {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    TestWorkflows.WorkflowWithUpdateAndSignal workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdateAndSignal.class, options);
    // To execute workflow client.execute() would do. But we want to start workflow and immediately
    // return.
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    assertEquals(workflowId, execution.getWorkflowId());

    SDKTestWorkflowRule.waitForOKQuery(workflow);
    assertEquals("initial", workflow.getState());

    for (int i = 0; i < 5; i++) {
      assertThrows(WorkflowUpdateException.class, () -> workflow.update(""));
    }

    workflow.getState();

    workflow.signal("signal 1");

    assertEquals("update 1", workflow.update("update 1"));

    workflow.signal("signal 2");

    workflow.complete();

    assertEquals(Arrays.asList("signal 1", "update 1", "signal 2"), workflow.execute());
    SDKTestWorkflowRule.assertNoHistoryEvent(
        workflowClient.fetchHistory(execution.getWorkflowId()).getHistory(),
        EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED);
  }

  public static class TestUpdateWithSignalWorkflowImpl
      implements TestWorkflows.WorkflowWithUpdateAndSignal {
    String state = "initial";
    List<String> updatesAndSignals = new ArrayList<>();
    CompletablePromise<Void> promise = Workflow.newPromise();
    TestActivities.VariousTestActivities localActivities =
        Workflow.newLocalActivityStub(
            TestActivities.VariousTestActivities.class, SDKTestOptions.newLocalActivityOptions());

    @Override
    public List<String> execute() {
      promise.get();
      return updatesAndSignals;
    }

    @Override
    public String getState() {
      return state;
    }

    @Override
    public void signal(String value) {
      updatesAndSignals.add(value);
    }

    @Override
    public String update(String value) {
      Workflow.sleep(100);
      updatesAndSignals.add(value);
      return value;
    }

    @Override
    public void validator(String value) {
      if (value.isEmpty()) {
        throw new RuntimeException("Empty value");
      }
    }

    @Override
    public void complete() {
      promise.complete(null);
    }
  }
}
