package io.temporal.workflow.signalTests;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.*;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;

public class SignalAllHandlersFinished {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestSignalWorkflowImpl.class).build();

  @Test
  public void isEveryHandlerFinished() {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    WorkflowWithSignal workflow = workflowClient.newWorkflowStub(WorkflowWithSignal.class, options);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);
    // Send a bunch of signals to the workflow
    for (int i = 0; i < 5; i++) {
      workflow.signal();
    }
    // Try to complete the workflow, expecting that it will block until all signals are processed
    workflow.tryComplete();
    assertEquals(5, workflow.execute());
  }

  @WorkflowInterface
  public interface WorkflowWithSignal {

    @WorkflowMethod
    int execute();

    @SignalMethod
    void signal();

    @SignalMethod
    void tryComplete();
  }

  public static class TestSignalWorkflowImpl implements WorkflowWithSignal {
    int handlersFinished = 0;
    CompletablePromise<Void> promise = Workflow.newPromise();

    @Override
    public int execute() {
      promise.get();
      Workflow.await(() -> Workflow.isEveryHandlerFinished());
      return handlersFinished;
    }

    @Override
    public void tryComplete() {
      promise.complete(null);
    }

    @Override
    public void signal() {
      Workflow.sleep(Duration.ofSeconds(5));
      handlersFinished++;
    }
  }
}
