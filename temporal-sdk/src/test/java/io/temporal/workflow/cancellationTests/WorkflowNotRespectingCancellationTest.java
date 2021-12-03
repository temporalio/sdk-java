package io.temporal.workflow.cancellationTests;

import static org.junit.Assert.*;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.util.concurrent.CompletableFuture;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowNotRespectingCancellationTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(SpinAndWaitWorkflow.Implementation.class)
          .setUseExternalService(false)
          .build();

  @Test
  public void cancelDuringSleep() {

    SpinAndWaitWorkflow.Interface workflowStub =
        testWorkflowRule.newWorkflowStub(SpinAndWaitWorkflow.Interface.class);

    int resultValue;

    try {
      WorkflowClient.start(workflowStub::executeWorkflow);
      WorkflowStub workflowControl = WorkflowStub.fromTyped(workflowStub);
      CompletableFuture<Integer> workflowResult = workflowControl.getResultAsync(Integer.class);

      SpinAndWaitWorkflow.getReadyToBeCancelledSignal().waitForSignalSafe();

      workflowControl.cancel();

      resultValue = workflowResult.get();

    } catch (Throwable err) {

      resultValue = SpinAndWaitWorkflow.Interface.FailureReturnCode;

      System.out.println(
          "\nUnexpected exception on the workflow client side ("
              + err.getClass().getName()
              + "): "
              + err.getMessage());
      err.printStackTrace();

      fail(
          "Unexpected exception ("
              + err.getClass().getName()
              + ") on the workflow client side. See above for details. Test will fail.");
    }

    if (resultValue == SpinAndWaitWorkflow.Interface.FailureReturnCode) {

      fail(
          "SpinAndWaitWorkflow completed with the result "
              + resultValue
              + ", indicating a failure within the workflow. See test output for additional details.");
    }

    assertEquals(0, resultValue);
  }
}
