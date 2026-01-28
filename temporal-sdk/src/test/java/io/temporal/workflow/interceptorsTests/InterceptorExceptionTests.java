package io.temporal.workflow.interceptorsTests;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowServiceException;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptorBase;
import io.temporal.common.interceptors.WorkflowClientInterceptorBase;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

public class InterceptorExceptionTests {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(WorkflowImpl.class)
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setInterceptors(new ExceptionOnStartThrowingClientInterceptor())
                  .validateAndBuildWithDefaults())
          .build();

  /**
   * Initiates Test Service shutdown as temporary to solution to long poll thread shutdown. See
   * issue: https://github.com/temporalio/sdk-java/issues/608
   */
  @After
  @SuppressWarnings("deprecation")
  public void tearDown() {
    testWorkflowRule.getTestEnvironment().shutdownTestService();
  }

  @Test
  public void testExceptionOnStart() {
    NoArgsWorkflow workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(NoArgsWorkflow.class);
    try {
      workflowStub.execute();
      fail("Workflow call is expected to fail with an exception");
    } catch (WorkflowServiceException e) {
      assertTrue(
          "An original exception should be preserved and passed",
          e.getCause() instanceof InterceptorException);
    }
  }

  public static class WorkflowImpl implements NoArgsWorkflow {
    @Override
    public void execute() {}
  }

  private static class ExceptionOnStartThrowingClientInterceptor
      extends WorkflowClientInterceptorBase {
    @Override
    public WorkflowClientCallsInterceptor workflowClientCallsInterceptor(
        WorkflowClientCallsInterceptor next) {
      return new WorkflowClientCallsInterceptorBase(next) {
        @Override
        public WorkflowStartOutput start(WorkflowStartInput input) {
          throw new InterceptorException();
        }
      };
    }
  }

  private static class InterceptorException extends RuntimeException {}
}
