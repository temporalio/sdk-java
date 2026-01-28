package io.temporal.workflow;

import io.temporal.activity.Activity;
import io.temporal.common.interceptors.*;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class GetInstanceTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new WorkerInterceptor())
                  .build())
          .setWorkflowTypes(TestWorkflow1Impl.class)
          .setActivityImplementations(new TestActivity1Impl())
          .build();

  @Test
  public void testGetInstance() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("TestWorkflow1Impl called TestActivity1Impl and TestActivity1Impl", result);
  }

  public static class TestActivity1Impl implements TestActivities.TestActivity1 {
    @Override
    public String execute(String input) {
      return Activity.getExecutionContext().getInstance().getClass().getSimpleName();
    }
  }

  public static class TestWorkflow1Impl implements TestWorkflow1 {

    private final TestActivities.TestActivity1 activities =
        Workflow.newActivityStub(
            TestActivities.TestActivity1.class,
            SDKTestOptions.newActivityOptions20sScheduleToClose());

    private final TestActivities.TestActivity1 localActivities =
        Workflow.newLocalActivityStub(
            TestActivities.TestActivity1.class,
            SDKTestOptions.newLocalActivityOptions20sScheduleToClose());

    @Override
    public String execute(String testName) {
      return Workflow.getInstance().getClass().getSimpleName()
          + " called "
          + activities.execute("")
          + " and "
          + localActivities.execute("");
    }
  }

  private static class WorkerInterceptor extends WorkerInterceptorBase {
    @Override
    public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
      return new WorkflowInboundCallsInterceptorBase(next) {
        @Override
        public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
          // Assert that Workflow.getInstance() is null when the interceptor is initialized
          Assert.assertNull(Workflow.getInstance());
          next.init(new WorkflowOutboundCallsInterceptorBase(outboundCalls));
        }

        @Override
        public WorkflowOutput execute(WorkflowInput input) {
          // Assert that Workflow.getInstance() is not null when the workflow is executed
          Assert.assertNotNull(Workflow.getInstance());
          return next.execute(input);
        }
      };
    }
  }
}
