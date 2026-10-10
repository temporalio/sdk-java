package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.PayloadLimitsTest.ActivityFailureTypeWorkflow;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * With enforcement disabled, the worker sends oversized completions and the server rejects them.
 */
public class PayloadLimitsDisabledTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(PayloadLimitsTest.ActivityFailureTypeWorkflowImpl.class)
          .setActivityImplementations(new PayloadLimitsTest.OversizedActivitiesImpl())
          .setWorkerOptions(WorkerOptions.newBuilder().setDisablePayloadErrorLimit(true).build())
          .build();

  @Before
  public void requireRealServer() {
    assumeTrue(
        "Needs a server that reports payload error limits", SDKTestWorkflowRule.useExternalService);
  }

  @Test
  public void anOversizedActivityResultIsLeftToTheServer() {
    ActivityFailureTypeWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ActivityFailureTypeWorkflow.class);
    // The server fails the activity itself, with a non-retryable ServerFailure instead of the
    // worker's retryable PayloadsTooLarge.
    assertEquals("ServerFailure", workflow.execute(false));
  }
}
