package io.temporal.internal.client;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com.google.common.reflect.TypeToken;
import io.temporal.client.ActivityExecutionDescription;
import io.temporal.client.ActivityFailedException;
import io.temporal.client.ActivityHandle;
import io.temporal.client.UntypedActivityHandle;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor.*;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
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
  @SuppressWarnings("unchecked")
  public void testGetResultTyped() throws Exception {
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
    when(interceptor.getActivityResultAsync(any(GetActivityResultInput.class)))
        .thenReturn(CompletableFuture.completedFuture(output));

    UntypedActivityHandle handle = new ActivityHandleImpl("id", null, interceptor);
    CompletableFuture<String> future = handle.getResultAsync(String.class);
    assertEquals("async-result", future.get());
  }

  @Test
  public void testDescribe() {
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
  public void testFromUntypedWrapsHandle() throws Exception {
    GetActivityResultOutput<String> output = mock(GetActivityResultOutput.class);
    when(output.getResult()).thenReturn("typed-result");
    when(interceptor.getActivityResult(any(GetActivityResultInput.class))).thenReturn(output);

    UntypedActivityHandle untyped = new ActivityHandleImpl("id", "run", interceptor);
    ActivityHandle<String> typed = ActivityHandle.fromUntyped(untyped, String.class);
    assertEquals("typed-result", typed.getResult());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testFromUntypedWithExplicitTypePassesTypeToInterceptor() throws Exception {
    // explicitType is a parameterized List<String> — distinct from List.class — so the verify
    // below can only pass if the implementation forwards the Type arg, not the Class arg.
    Type explicitType = new TypeToken<List<String>>() {}.getType();
    GetActivityResultOutput<List<String>> output = mock(GetActivityResultOutput.class);
    when(output.getResult()).thenReturn(Collections.singletonList("item"));
    when(interceptor.getActivityResult(any(GetActivityResultInput.class))).thenReturn(output);

    UntypedActivityHandle untyped = new ActivityHandleImpl("id", "run", interceptor);
    ActivityHandle<List<String>> typed =
        ActivityHandle.fromUntyped(
            untyped, (Class<List<String>>) (Class<?>) List.class, explicitType);
    typed.getResult();
    verify(interceptor).getActivityResult(argThat(i -> explicitType.equals(i.getResultType())));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testFromUntypedGetResultAsyncNoArg() throws Exception {
    GetActivityResultOutput<String> output = mock(GetActivityResultOutput.class);
    when(output.getResult()).thenReturn("async-typed");
    when(interceptor.getActivityResultAsync(any(GetActivityResultInput.class)))
        .thenReturn(CompletableFuture.completedFuture(output));

    UntypedActivityHandle untyped = new ActivityHandleImpl("id", "run", interceptor);
    ActivityHandle<String> typed = ActivityHandle.fromUntyped(untyped, String.class);
    CompletableFuture<String> future = typed.getResultAsync();
    assertEquals("async-typed", future.get());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testGetResultAsyncPropagatesActivityFailedExceptionAsCause() throws Exception {
    ActivityFailedException failure =
        new ActivityFailedException(
            "activity failed", "id", "run", new RuntimeException("root cause"));
    CompletableFuture<GetActivityResultOutput<String>> failed = new CompletableFuture<>();
    failed.completeExceptionally(failure);
    when(interceptor.getActivityResultAsync(any(GetActivityResultInput.class))).thenReturn(failed);

    UntypedActivityHandle handle = new ActivityHandleImpl("id", "run", interceptor);
    CompletableFuture<String> future = handle.getResultAsync(String.class);
    try {
      future.get();
      fail("expected ExecutionException");
    } catch (java.util.concurrent.ExecutionException e) {
      assertSame(failure, e.getCause());
    }
  }
}
