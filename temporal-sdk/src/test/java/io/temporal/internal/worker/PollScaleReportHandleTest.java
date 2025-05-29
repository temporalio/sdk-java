package io.temporal.internal.worker;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.workflow.Functions;
import org.junit.Test;
import org.mockito.Mockito;

public class PollScaleReportHandleTest {

  @Test
  public void handleResourceExhaustedError() {
    // Mock dependencies
    Functions.Proc1<Integer> mockScaleCallback = Mockito.mock(Functions.Proc1.class);
    PollScaleReportHandle<ScalingTask> handle =
        new PollScaleReportHandle<>(1, 10, 8, mockScaleCallback);

    // Simulate RESOURCE_EXHAUSTED error
    StatusRuntimeException exception = new StatusRuntimeException(Status.RESOURCE_EXHAUSTED);
    handle.report(null, exception);

    // Verify target poller count is halved and callback is invoked
    Mockito.verify(mockScaleCallback).apply(4);
  }

  @Test
  public void handleGenericError() {
    // Mock dependencies
    Functions.Proc1<Integer> mockScaleCallback = Mockito.mock(Functions.Proc1.class);
    PollScaleReportHandle<ScalingTask> handle =
        new PollScaleReportHandle<>(1, 10, 5, mockScaleCallback);

    // Simulate a generic error
    handle.report(null, new RuntimeException("Generic error"));

    // Verify target poller count is decremented and callback is invoked
    Mockito.verify(mockScaleCallback).apply(4);
  }

  @Test
  public void applyScalingDecisionDeltaWhenAllowed() {
    // Mock dependencies
    Functions.Proc1<Integer> mockScaleCallback = Mockito.mock(Functions.Proc1.class);
    ScalingTask mockTask = Mockito.mock(ScalingTask.class);
    ScalingTask.ScalingDecision mockDecision = Mockito.mock(ScalingTask.ScalingDecision.class);
    Mockito.when(mockTask.getScalingDecision()).thenReturn(mockDecision);
    Mockito.when(mockDecision.getPollRequestDeltaSuggestion()).thenReturn(3);

    PollScaleReportHandle<ScalingTask> handle =
        new PollScaleReportHandle<>(1, 10, 5, mockScaleCallback);
    handle.run(); // Enable scale-up

    // Report a task with a scaling decision
    handle.report(mockTask, null);

    // Verify target poller count is updated and callback is invoked
    Mockito.verify(mockScaleCallback).apply(8);
  }
}
