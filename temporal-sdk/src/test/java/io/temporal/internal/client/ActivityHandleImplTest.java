package io.temporal.internal.client;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import io.temporal.client.ActivityCancelOptions;
import io.temporal.client.ActivityDescribeOptions;
import io.temporal.client.ActivityExecutionDescription;
import io.temporal.client.ActivityFailedException;
import io.temporal.client.ActivityHandle;
import io.temporal.client.ActivityTerminateOptions;
import io.temporal.client.UntypedActivityHandle;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor.*;
import java.util.concurrent.CompletableFuture;
import org.junit.Before;
import org.junit.Test;

public class ActivityHandleImplTest {

  private ActivityClientCallsInterceptor interceptor;

  @Before
  public void setUp() {
    interceptor = mock(ActivityClientCallsInterceptor.class);
  }

  @Test
  public void testGetActivityId() {
    UntypedActivityHandle handle = new ActivityHandleImpl("act-id", "run-id", interceptor);
    assertEquals("act-id", handle.getActivityId());
  }

  @Test
  public void testGetActivityRunId() {
    UntypedActivityHandle handle = new ActivityHandleImpl("act-id", "run-id", interceptor);
    assertEquals("run-id", handle.getActivityRunId());
  }

  @Test
  public void testNullRunId() {
    UntypedActivityHandle handle = new ActivityHandleImpl("act-id", null, interceptor);
    assertNull(handle.getActivityRunId());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testGetResultTyped() throws ActivityFailedException {
    GetActivityResultOutput<String> output = mock(GetActivityResultOutput.class);
    when(output.getResult()).thenReturn("hello");
    when(interceptor.getActivityResult(any(GetActivityResultInput.class))).thenReturn(output);

    UntypedActivityHandle handle = new ActivityHandleImpl("id", "run", interceptor);
    String result = handle.getResult(String.class);
    assertEquals("hello", result);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testGetResultAsync() throws Exception {
    GetActivityResultOutput<String> output = mock(GetActivityResultOutput.class);
    when(output.getResult()).thenReturn("async-result");
    when(interceptor.getActivityResult(any(GetActivityResultInput.class))).thenReturn(output);

    UntypedActivityHandle handle = new ActivityHandleImpl("id", null, interceptor);
    CompletableFuture<String> future = handle.getResultAsync(String.class);
    assertEquals("async-result", future.get());
  }

  @Test
  public void testDescribeNoOptions() {
    ActivityExecutionDescription desc = mock(ActivityExecutionDescription.class);
    DescribeActivityOutput output = new DescribeActivityOutput(desc);
    when(interceptor.describeActivity(any(DescribeActivityInput.class))).thenReturn(output);

    UntypedActivityHandle handle = new ActivityHandleImpl("id", "run", interceptor);
    ActivityExecutionDescription result = handle.describe();
    assertSame(desc, result);
    verify(interceptor).describeActivity(argThat(i -> "id".equals(i.getId())));
  }

  @Test
  public void testDescribeWithOptions() {
    ActivityExecutionDescription desc = mock(ActivityExecutionDescription.class);
    DescribeActivityOutput output = new DescribeActivityOutput(desc);
    when(interceptor.describeActivity(any(DescribeActivityInput.class))).thenReturn(output);

    UntypedActivityHandle handle = new ActivityHandleImpl("id", null, interceptor);
    ActivityDescribeOptions opts = ActivityDescribeOptions.newBuilder().build();
    handle.describe(opts);
    verify(interceptor)
        .describeActivity(argThat(i -> "id".equals(i.getId()) && opts.equals(i.getOptions())));
  }

  @Test
  public void testCancelNoOptions() {
    when(interceptor.cancelActivity(any(CancelActivityInput.class)))
        .thenReturn(new CancelActivityOutput());

    UntypedActivityHandle handle = new ActivityHandleImpl("id", "run", interceptor);
    handle.cancel();
    verify(interceptor).cancelActivity(argThat(i -> "id".equals(i.getId())));
  }

  @Test
  public void testCancelWithOptions() {
    when(interceptor.cancelActivity(any(CancelActivityInput.class)))
        .thenReturn(new CancelActivityOutput());

    UntypedActivityHandle handle = new ActivityHandleImpl("id", null, interceptor);
    ActivityCancelOptions opts = ActivityCancelOptions.newBuilder().build();
    handle.cancel(opts);
    verify(interceptor)
        .cancelActivity(argThat(i -> "id".equals(i.getId()) && opts.equals(i.getOptions())));
  }

  @Test
  public void testTerminateNoOptions() {
    when(interceptor.terminateActivity(any(TerminateActivityInput.class)))
        .thenReturn(new TerminateActivityOutput());

    UntypedActivityHandle handle = new ActivityHandleImpl("id", "run", interceptor);
    handle.terminate("reason");
    verify(interceptor)
        .terminateActivity(argThat(i -> "id".equals(i.getId()) && "reason".equals(i.getReason())));
  }

  @Test
  public void testTerminateWithOptions() {
    when(interceptor.terminateActivity(any(TerminateActivityInput.class)))
        .thenReturn(new TerminateActivityOutput());

    UntypedActivityHandle handle = new ActivityHandleImpl("id", null, interceptor);
    ActivityTerminateOptions opts = ActivityTerminateOptions.newBuilder().build();
    handle.terminate("done", opts);
    verify(interceptor)
        .terminateActivity(
            argThat(
                i ->
                    "id".equals(i.getId())
                        && "done".equals(i.getReason())
                        && opts.equals(i.getOptions())));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testFromUntypedWrapsHandle() throws ActivityFailedException {
    GetActivityResultOutput<String> output = mock(GetActivityResultOutput.class);
    when(output.getResult()).thenReturn("typed-result");
    when(interceptor.getActivityResult(any(GetActivityResultInput.class))).thenReturn(output);

    UntypedActivityHandle untyped = new ActivityHandleImpl("id", "run", interceptor);
    ActivityHandle<String> typed = ActivityHandle.fromUntyped(untyped, String.class);
    assertEquals("typed-result", typed.getResult());
  }
}
