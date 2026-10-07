package io.temporal.client;

import static io.temporal.testUtils.Eventually.assertEventually;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptorBase;
import io.temporal.common.interceptors.WorkflowClientInterceptorBase;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;

public class ListWorkflowExecutionsInterceptorTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflows.DoNothingNoArgsWorkflow.class)
          .build();

  @Test
  public void listExecutions_isIntercepted() {
    assumeTrue(
        "Test Server doesn't support listWorkflowExecutions endpoint yet",
        SDKTestWorkflowRule.useExternalService);

    AtomicInteger intercepted = new AtomicInteger();
    WorkflowClient workflowClient =
        WorkflowClient.newInstance(
            testWorkflowRule.getWorkflowServiceStubs(),
            WorkflowClientOptions.newBuilder(testWorkflowRule.getWorkflowClient().getOptions())
                .setInterceptors(
                    new WorkflowClientInterceptorBase() {
                      @Override
                      public WorkflowClientCallsInterceptor workflowClientCallsInterceptor(
                          WorkflowClientCallsInterceptor next) {
                        return new WorkflowClientCallsInterceptorBase(next) {
                          @Override
                          public ListWorkflowExecutionsOutput listWorkflowExecutions(
                              ListWorkflowExecutionsInput input) {
                            intercepted.incrementAndGet();
                            return super.listWorkflowExecutions(input);
                          }
                        };
                      }
                    })
                .validateAndBuildWithDefaults());

    WorkflowStub.fromTyped(testWorkflowRule.newWorkflowStub(TestWorkflows.NoArgsWorkflow.class))
        .start();

    String query = "TaskQueue='" + testWorkflowRule.getTaskQueue() + "'";
    // Wait through the unintercepted client so the assertion below still verifies exactly one
    // intercepted call.
    assertEventually(
        Duration.ofSeconds(10),
        () ->
            assertTrue(
                testWorkflowRule.getWorkflowClient().listExecutions(query).findAny().isPresent()));
    java.util.List<WorkflowExecutionMetadata> result =
        workflowClient.listExecutions(query).collect(java.util.stream.Collectors.toList());
    assertFalse(result.isEmpty());
    assertEquals(1, intercepted.get());
  }
}
