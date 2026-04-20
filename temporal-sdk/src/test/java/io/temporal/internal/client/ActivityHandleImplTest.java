package io.temporal.internal.client;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import io.temporal.client.ActivityExecutionDescription;
import io.temporal.client.ActivityFailedException;
import io.temporal.client.ActivityHandle;
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
  public void testCancelNoReason() {
    when(interceptor.cancelActivity(any(CancelActivityInput.class)))
        .thenReturn(new CancelActivityOutput());

    UntypedActivityHandle handle = new ActivityHandleImpl("id", "run", interceptor);
    handle.cancel();
    verify(interceptor)
        .cancelActivity(argThat(i -> "id".equals(i.getId()) && i.getReason() == null));
  }

  @Test
  public void testCancelWithReason() {
    when(interceptor.cancelActivity(any(CancelActivityInput.class)))
        .thenReturn(new CancelActivityOutput());

    UntypedActivityHandle handle = new ActivityHandleImpl("id", null, interceptor);
    handle.cancel("cancel-reason");
    verify(interceptor)
        .cancelActivity(
            argThat(i -> "id".equals(i.getId()) && "cancel-reason".equals(i.getReason())));
  }

  @Test
  public void testTerminateNoReason() {
    when(interceptor.terminateActivity(any(TerminateActivityInput.class)))
        .thenReturn(new TerminateActivityOutput());

    UntypedActivityHandle handle = new ActivityHandleImpl("id", "run", interceptor);
    handle.terminate();
    verify(interceptor)
        .terminateActivity(argThat(i -> "id".equals(i.getId()) && i.getReason() == null));
  }

  @Test
  public void testTerminateWithReason() {
    when(interceptor.terminateActivity(any(TerminateActivityInput.class)))
        .thenReturn(new TerminateActivityOutput());

    UntypedActivityHandle handle = new ActivityHandleImpl("id", null, interceptor);
    handle.terminate("done");
    verify(interceptor)
        .terminateActivity(argThat(i -> "id".equals(i.getId()) && "done".equals(i.getReason())));
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

  @Test
  @SuppressWarnings("unchecked")
  public void testFromUntypedWithExplicitTypePassesTypeToInterceptor()
      throws ActivityFailedException {
    GetActivityResultOutput<String> output = mock(GetActivityResultOutput.class);
    when(output.getResult()).thenReturn("generic-result");
    when(interceptor.getActivityResult(any(GetActivityResultInput.class))).thenReturn(output);

    java.lang.reflect.Type explicitType = String.class;
    UntypedActivityHandle untyped = new ActivityHandleImpl("id", "run", interceptor);
    ActivityHandle<String> typed = ActivityHandle.fromUntyped(untyped, String.class, explicitType);
    assertEquals("generic-result", typed.getResult());
    verify(interceptor).getActivityResult(argThat(i -> explicitType.equals(i.getResultType())));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testFromUntypedGetResultAsyncNoArg() throws Exception {
    GetActivityResultOutput<String> output = mock(GetActivityResultOutput.class);
    when(output.getResult()).thenReturn("async-typed");
    when(interceptor.getActivityResult(any(GetActivityResultInput.class))).thenReturn(output);

    UntypedActivityHandle untyped = new ActivityHandleImpl("id", "run", interceptor);
    ActivityHandle<String> typed = ActivityHandle.fromUntyped(untyped, String.class);
    CompletableFuture<String> future = typed.getResultAsync();
    assertEquals("async-typed", future.get());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testGetResultAsyncWrapsActivityFailedExceptionInRuntimeException() throws Exception {
    ActivityFailedException failure =
        new ActivityFailedException("activity failed", new RuntimeException("root cause"));
    when(interceptor.getActivityResult(any(GetActivityResultInput.class))).thenThrow(failure);

    UntypedActivityHandle handle = new ActivityHandleImpl("id", "run", interceptor);
    CompletableFuture<String> future = handle.getResultAsync(String.class);
    try {
      future.get();
      fail("expected ExecutionException");
    } catch (java.util.concurrent.ExecutionException e) {
      assertSame(failure, e.getCause().getCause());
    }
  }
}
