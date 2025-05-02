package io.temporal.client.functional;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

/**
 * These tests are covering the situation when a getResult wait time crosses a boundary of when
 * server cuts the long poll and returns an empty response to a history long poll. It's 20 seconds.
 *
 * <p>It has a sync version in {@link GetResultsAsyncOverMaximumLongPollWaitTest} that is split to
 * reduce the total execution time
 */
public class GetResultsSyncOverMaximumLongPollWaitTest {
  private static final int HISTORY_LONG_POLL_TIMEOUT_SECONDS = 20;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setUseTimeskipping(false)
          .setWorkflowTypes(TestWorkflowImpl.class)
          .build();

  @Test(timeout = 2 * HISTORY_LONG_POLL_TIMEOUT_SECONDS * 1000)
  public void testGetResults() {
    TestWorkflows.NoArgsWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.NoArgsWorkflow.class);
    WorkflowClient.start(workflow::execute);
    WorkflowStub.fromTyped(workflow).getResult(Void.class);
  }

  public static class TestWorkflowImpl implements TestWorkflows.NoArgsWorkflow {
    @Override
    public void execute() {
      Workflow.sleep(Duration.ofSeconds(3 * HISTORY_LONG_POLL_TIMEOUT_SECONDS / 2));
    }
  }
}
