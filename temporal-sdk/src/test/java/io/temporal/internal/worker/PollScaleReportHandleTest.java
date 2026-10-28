package io.temporal.internal.worker;

import static org.junit.Assert.assertEquals;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.workflow.Functions;
import java.util.concurrent.atomic.AtomicInteger;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

@RunWith(JUnitParamsRunner.class)
public class PollScaleReportHandleTest {

  @Test
  @Parameters({
    "5, 1, true, 6",
    "5, 3, true, 8",
    "5, -1, true, 4",
    "6, -3, true, 3",
    "5, 1, false, 5",
    "5, 3, false, 5",
    "5, -1, false, 4",
    "6, -3, false, 3",
    "5, 20, true, 10",
    "5, -20, true, 2",
    "10, 1, true, 10",
    "2, -1, true, 2",
    "5, 0, true, 5"
  })
  public void applyScalingDecision(int initial, int delta, boolean scaleUpAllowed, int expected) {
    AtomicInteger target = new AtomicInteger(initial);
    PollScaleReportHandle<ScalingTask> handle =
        new PollScaleReportHandle<>(2, 10, initial, false, target::set);
    if (scaleUpAllowed) {
      // An initial reporting period allows scale-up because both task counts are zero.
      handle.run();
    }
    ScalingTask task = () -> new ScalingTask.ScalingDecision(delta);

    handle.report(task, null);

    assertEquals(expected, target.get());
  }

  @Test
  public void handleResourceExhaustedError() {
    // Mock dependencies
    Functions.Proc1<Integer> mockScaleCallback = Mockito.mock(Functions.Proc1.class);
    ScalingTask mockTask = Mockito.mock(ScalingTask.class);
    ScalingTask.ScalingDecision mockDecision = Mockito.mock(ScalingTask.ScalingDecision.class);
    Mockito.when(mockTask.getScalingDecision()).thenReturn(mockDecision);
    Mockito.when(mockDecision.getPollRequestDeltaSuggestion()).thenReturn(0);
    PollScaleReportHandle<ScalingTask> handle =
        new PollScaleReportHandle<>(1, 10, 8, false, mockScaleCallback);

    // Simulate RESOURCE_EXHAUSTED error
    StatusRuntimeException exception = new StatusRuntimeException(Status.RESOURCE_EXHAUSTED);
    handle.report(mockTask, null);
    handle.report(null, exception);

    // Verify target poller count is halved and callback is invoked
    Mockito.verify(mockScaleCallback).apply(4);
  }

  @Test
  public void handleGenericError() {
    // Mock dependencies
    Functions.Proc1<Integer> mockScaleCallback = Mockito.mock(Functions.Proc1.class);
    ScalingTask mockTask = Mockito.mock(ScalingTask.class);
    ScalingTask.ScalingDecision mockDecision = Mockito.mock(ScalingTask.ScalingDecision.class);
    Mockito.when(mockTask.getScalingDecision()).thenReturn(mockDecision);
    Mockito.when(mockDecision.getPollRequestDeltaSuggestion()).thenReturn(0);
    PollScaleReportHandle<ScalingTask> handle =
        new PollScaleReportHandle<>(1, 10, 5, false, mockScaleCallback);

    // Simulate a generic error
    handle.report(mockTask, null);
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
        new PollScaleReportHandle<>(1, 10, 5, false, mockScaleCallback);
    handle.run(); // Enable scale-up

    // Report a task with a scaling decision
    handle.report(mockTask, null);

    // Verify target poller count is updated and callback is invoked
    Mockito.verify(mockScaleCallback).apply(8);
  }

  @Test
  public void scaleDownOnEmptyPollWhenServerSupportsAutoscaling() {
    Functions.Proc1<Integer> mockScaleCallback = Mockito.mock(Functions.Proc1.class);
    PollScaleReportHandle<ScalingTask> handle =
        new PollScaleReportHandle<>(1, 10, 5, true, mockScaleCallback);

    // Empty poll (null task, no error) should scale down when server supports autoscaling,
    // even without having seen a scaling decision.
    handle.report(null, null);

    Mockito.verify(mockScaleCallback).apply(4);
  }

  @Test
  public void noScaleDownOnEmptyPollWithoutAutoscalingOrDecision() {
    Functions.Proc1<Integer> mockScaleCallback = Mockito.mock(Functions.Proc1.class);
    PollScaleReportHandle<ScalingTask> handle =
        new PollScaleReportHandle<>(1, 10, 5, false, mockScaleCallback);

    // Empty poll should NOT scale down when server doesn't support autoscaling
    // and we haven't seen a scaling decision.
    handle.report(null, null);

    Mockito.verifyNoInteractions(mockScaleCallback);
  }

  @Test
  public void scaleDownOnErrorWhenServerSupportsAutoscaling() {
    Functions.Proc1<Integer> mockScaleCallback = Mockito.mock(Functions.Proc1.class);
    PollScaleReportHandle<ScalingTask> handle =
        new PollScaleReportHandle<>(1, 10, 5, true, mockScaleCallback);

    // Error should scale down when server supports autoscaling,
    // even without a prior scaling decision.
    handle.report(null, new RuntimeException("error"));

    Mockito.verify(mockScaleCallback).apply(4);
  }

  @Test
  public void noScaleDownOnErrorWithoutAutoscalingOrDecision() {
    Functions.Proc1<Integer> mockScaleCallback = Mockito.mock(Functions.Proc1.class);
    PollScaleReportHandle<ScalingTask> handle =
        new PollScaleReportHandle<>(1, 10, 5, false, mockScaleCallback);

    // Error should NOT scale down without autoscaling support or prior scaling decision.
    handle.report(null, new RuntimeException("error"));

    Mockito.verifyNoInteractions(mockScaleCallback);
  }

  @Test
  public void scaleDownOnEmptyPollRespectsMinPollerCount() {
    Functions.Proc1<Integer> mockScaleCallback = Mockito.mock(Functions.Proc1.class);
    // min=3, initial=3, so scale down should be clamped
    PollScaleReportHandle<ScalingTask> handle =
        new PollScaleReportHandle<>(3, 10, 3, true, mockScaleCallback);

    // Empty polls should not scale below minimum
    for (int i = 0; i < 5; i++) {
      handle.report(null, null);
    }

    // Should never have been called since target can't go below min
    Mockito.verifyNoInteractions(mockScaleCallback);
  }
}
