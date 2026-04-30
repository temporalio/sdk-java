package io.temporal.common.interceptors;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import io.temporal.client.ActivityExecutionCount;
import io.temporal.client.ActivityExecutionDescription;
import io.temporal.client.ActivityExecutionMetadata;
import io.temporal.client.StartActivityOptions;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor.*;
import java.time.Duration;
import java.util.Collections;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests that {@link ActivityClientCallsInterceptorBase} correctly delegates all activity-related
 * methods to the next interceptor in the chain.
 */
public class ActivityClientCallsInterceptorBaseTest {

  private ActivityClientCallsInterceptor next;
  private ActivityClientCallsInterceptorBase base;

  @Before
  public void setUp() {
    next = mock(ActivityClientCallsInterceptor.class);
    base =
        new ActivityClientCallsInterceptorBase(next) {
          // concrete subclass for testing
        };
  }

  private static StartActivityOptions minimalOptions() {
    return StartActivityOptions.newBuilder()
        .setId("act-id")
        .setTaskQueue("tq")
        .setStartToCloseTimeout(Duration.ofSeconds(10))
        .build();
  }

  @Test
  public void testStartActivityDelegatesToNext() {
    StartActivityOutput output = new StartActivityOutput("act-id", null);
    when(next.startActivity(any(StartActivityInput.class))).thenReturn(output);

    StartActivityInput input =
        new StartActivityInput(
            "MyActivity", Collections.emptyList(), minimalOptions(), Header.empty());
    StartActivityOutput result = base.startActivity(input);

    assertSame(output, result);
    verify(next).startActivity(input);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testGetActivityResultDelegatesToNext() throws Exception {
    GetActivityResultOutput<String> output = mock(GetActivityResultOutput.class);
    when(next.getActivityResult(any(GetActivityResultInput.class))).thenReturn(output);

    GetActivityResultInput<String> input = new GetActivityResultInput<>("id", null, String.class);
    GetActivityResultOutput<String> result = base.getActivityResult(input);

    assertSame(output, result);
    verify(next).getActivityResult(input);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testGetActivityResultAsyncDelegatesToNext() {
    GetActivityResultOutput<String> output = mock(GetActivityResultOutput.class);
    java.util.concurrent.CompletableFuture<GetActivityResultOutput<String>> future =
        java.util.concurrent.CompletableFuture.completedFuture(output);
    when(next.getActivityResultAsync(any(GetActivityResultInput.class))).thenReturn(future);

    GetActivityResultInput<String> input = new GetActivityResultInput<>("id", null, String.class);
    java.util.concurrent.CompletableFuture<GetActivityResultOutput<String>> result =
        base.getActivityResultAsync(input);

    assertSame(future, result);
    verify(next).getActivityResultAsync(input);
  }

  @Test
  public void testDescribeActivityDelegatesToNext() {
    ActivityExecutionDescription desc = mock(ActivityExecutionDescription.class);
    DescribeActivityOutput output = new DescribeActivityOutput(desc);
    when(next.describeActivity(any(DescribeActivityInput.class))).thenReturn(output);

    DescribeActivityInput input = new DescribeActivityInput("id", null);
    DescribeActivityOutput result = base.describeActivity(input);

    assertSame(output, result);
    verify(next).describeActivity(input);
  }

  @Test
  public void testCancelActivityDelegatesToNext() {
    CancelActivityOutput output = new CancelActivityOutput();
    when(next.cancelActivity(any(CancelActivityInput.class))).thenReturn(output);

    CancelActivityInput input = new CancelActivityInput("id", null, "cancel-reason");
    CancelActivityOutput result = base.cancelActivity(input);

    assertSame(output, result);
    verify(next).cancelActivity(input);
  }

  @Test
  public void testTerminateActivityDelegatesToNext() {
    TerminateActivityOutput output = new TerminateActivityOutput();
    when(next.terminateActivity(any(TerminateActivityInput.class))).thenReturn(output);

    TerminateActivityInput input = new TerminateActivityInput("id", null, "reason");
    TerminateActivityOutput result = base.terminateActivity(input);

    assertSame(output, result);
    verify(next).terminateActivity(input);
  }

  @Test
  public void testListActivitiesDelegatesToNext() {
    Stream<ActivityExecutionMetadata> stream = Stream.empty();
    ListActivitiesOutput output = new ListActivitiesOutput(stream);
    when(next.listActivities(any(ListActivitiesInput.class))).thenReturn(output);

    ListActivitiesInput input = new ListActivitiesInput("query");
    ListActivitiesOutput result = base.listActivities(input);

    assertSame(output, result);
    verify(next).listActivities(input);
  }

  @Test
  public void testCountActivitiesDelegatesToNext() {
    ActivityExecutionCount count = mock(ActivityExecutionCount.class);
    CountActivitiesOutput output = new CountActivitiesOutput(count);
    when(next.countActivities(any(CountActivitiesInput.class))).thenReturn(output);

    CountActivitiesInput input = new CountActivitiesInput("query");
    CountActivitiesOutput result = base.countActivities(input);

    assertSame(output, result);
    verify(next).countActivities(input);
  }
}
