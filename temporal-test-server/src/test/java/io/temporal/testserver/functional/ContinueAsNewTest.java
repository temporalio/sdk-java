package io.temporal.testserver.functional;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.common.interceptors.*;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testserver.functional.common.TestWorkflows;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.ContinueAsNewOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ContinueAsNewTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new StripsTqFromCanInterceptor())
                  .build())
          .setWorkflowTypes(TestWorkflow.class)
          .build();

  @Test
  public void repeatedFailure() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();

    TestWorkflows.WorkflowTakesBool workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.WorkflowTakesBool.class, options);
    workflowStub.execute(true);

    WorkflowExecution execution = WorkflowStub.fromTyped(workflowStub).getExecution();
    // Verify the latest history is from being CaN'd
    WorkflowExecutionHistory lastHist =
        testWorkflowRule.getExecutionHistory(execution.getWorkflowId());
    Assert.assertFalse(
        lastHist
            .getEvents()
            .get(0)
            .getWorkflowExecutionStartedEventAttributes()
            .getFirstExecutionRunId()
            .isEmpty());
  }

  public static class TestWorkflow implements TestWorkflows.WorkflowTakesBool {
    @Override
    public void execute(boolean doContinue) {
      if (doContinue) {
        Workflow.continueAsNew(false);
      }
    }
  }

  // Verify that we can strip the TQ name and test server continues onto same TQ
  private static class StripsTqFromCanInterceptor extends WorkerInterceptorBase {
    @Override
    public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
      return new WorkflowInboundCallsInterceptorBase(next) {
        @Override
        public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
          next.init(new StripsTqFromCanCmd(outboundCalls));
        }
      };
    }
  }

  private static class StripsTqFromCanCmd extends WorkflowOutboundCallsInterceptorBase {

    private final WorkflowOutboundCallsInterceptor next;

    public StripsTqFromCanCmd(WorkflowOutboundCallsInterceptor next) {
      super(next);
      this.next = next;
    }

    @Override
    public void continueAsNew(ContinueAsNewInput input) {
      next.continueAsNew(
          new ContinueAsNewInput(
              input.getWorkflowType(),
              ContinueAsNewOptions.newBuilder(input.getOptions()).setTaskQueue(null).build(),
              input.getArgs(),
              input.getHeader()));
    }
  }
}
