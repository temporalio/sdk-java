package io.temporal.opentracing;

import static org.junit.Assert.*;

import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.util.ThreadLocalScopeManager;
import io.temporal.api.workflowservice.v1.CountActivityExecutionsResponse;
import io.temporal.client.ActivityExecutionCount;
import io.temporal.client.StartActivityOptions;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import io.temporal.common.interceptors.ActivityClientCallsInterceptorBase;
import io.temporal.common.interceptors.Header;
import io.temporal.opentracing.internal.ContextAccessor;
import io.temporal.opentracing.internal.OpenTracingActivityClientCallsInterceptor;
import io.temporal.opentracing.internal.SpanFactory;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for standalone activity tracing on the client side. */
public class StandaloneActivityClientTracingTest {

  private final MockTracer mockTracer =
      new MockTracer(new ThreadLocalScopeManager(), MockTracer.Propagator.TEXT_MAP);

  private final OpenTracingOptions otOptions =
      OpenTracingOptions.newBuilder().setTracer(mockTracer).build();

  private OpenTracingActivityClientCallsInterceptor interceptor;

  @Before
  public void setUp() {
    mockTracer.reset();
    interceptor =
        new OpenTracingActivityClientCallsInterceptor(
            new StubActivityClientCallsInterceptor(),
            otOptions,
            new SpanFactory(otOptions),
            new ContextAccessor(otOptions));
  }

  @After
  public void tearDown() {
    mockTracer.reset();
  }

  @Test
  public void testStartActivityCreatesSpanWithHeaderPropagation() {
    StartActivityOptions opts =
        StartActivityOptions.newBuilder()
            .setId("act-123")
            .setTaskQueue("tq")
            .setScheduleToCloseTimeout(Duration.ofMinutes(1))
            .build();
    Header header = Header.empty();
    ActivityClientCallsInterceptor.StartActivityInput input =
        new ActivityClientCallsInterceptor.StartActivityInput(
            "MyActivity", Collections.emptyList(), opts, header);

    interceptor.startActivity(input);

    List<MockSpan> spans = mockTracer.finishedSpans();
    assertEquals(1, spans.size());
    MockSpan span = spans.get(0);
    assertEquals("StartActivity:MyActivity", span.operationName());
    assertEquals("act-123", span.tags().get("activityId"));
    assertFalse("Trace context should be propagated into header", header.getValues().isEmpty());
  }

  @Test
  public void testStartActivitySpanIsChildOfActiveSpan() {
    MockSpan parentSpan = mockTracer.buildSpan("ClientFunction").start();
    try (io.opentracing.Scope ignored = mockTracer.scopeManager().activate(parentSpan)) {
      StartActivityOptions opts =
          StartActivityOptions.newBuilder()
              .setId("act-child")
              .setTaskQueue("tq")
              .setScheduleToCloseTimeout(Duration.ofMinutes(1))
              .build();
      interceptor.startActivity(
          new ActivityClientCallsInterceptor.StartActivityInput(
              "MyActivity", Collections.emptyList(), opts, Header.empty()));
    } finally {
      parentSpan.finish();
    }

    List<MockSpan> spans = mockTracer.finishedSpans();
    assertEquals(2, spans.size());

    MockSpan activitySpan = spans.get(0);
    assertEquals("StartActivity:MyActivity", activitySpan.operationName());
    assertEquals(parentSpan.context().spanId(), activitySpan.parentId());
  }

  @Test
  public void testManagementCallsDoNotCreateSpans() throws TimeoutException {
    interceptor.getActivityResult(
        new ActivityClientCallsInterceptor.GetActivityResultInput<>(
            "act-result", null, String.class));
    interceptor.getActivityResultAsync(
        new ActivityClientCallsInterceptor.GetActivityResultInput<>(
            "act-result-async", null, String.class));
    interceptor.describeActivity(
        new ActivityClientCallsInterceptor.DescribeActivityInput("act-desc", null));
    interceptor.cancelActivity(
        new ActivityClientCallsInterceptor.CancelActivityInput("act-cancel", null, "reason"));
    interceptor.terminateActivity(
        new ActivityClientCallsInterceptor.TerminateActivityInput("act-term", null, "reason"));
    interceptor.listActivities(
        new ActivityClientCallsInterceptor.ListActivitiesInput("TaskQueue = 'tq'"));
    interceptor.countActivities(
        new ActivityClientCallsInterceptor.CountActivitiesInput("TaskQueue = 'tq'"));

    assertTrue(mockTracer.finishedSpans().isEmpty());
  }

  private static class StubActivityClientCallsInterceptor
      extends ActivityClientCallsInterceptorBase {

    StubActivityClientCallsInterceptor() {
      super(null);
    }

    @Override
    public StartActivityOutput startActivity(StartActivityInput input) {
      return new StartActivityOutput(input.getOptions().getId(), null);
    }

    @Override
    public <R> GetActivityResultOutput<R> getActivityResult(GetActivityResultInput<R> input)
        throws TimeoutException {
      return new GetActivityResultOutput<>(null);
    }

    @Override
    public <R> CompletableFuture<GetActivityResultOutput<R>> getActivityResultAsync(
        GetActivityResultInput<R> input) {
      return CompletableFuture.completedFuture(new GetActivityResultOutput<>(null));
    }

    @Override
    public DescribeActivityOutput describeActivity(DescribeActivityInput input) {
      return new DescribeActivityOutput(null);
    }

    @Override
    public CancelActivityOutput cancelActivity(CancelActivityInput input) {
      return new CancelActivityOutput();
    }

    @Override
    public TerminateActivityOutput terminateActivity(TerminateActivityInput input) {
      return new TerminateActivityOutput();
    }

    @Override
    public ListActivitiesOutput listActivities(ListActivitiesInput input) {
      return new ListActivitiesOutput(Stream.empty());
    }

    @Override
    public CountActivitiesOutput countActivities(CountActivitiesInput input) {
      return new CountActivitiesOutput(
          new ActivityExecutionCount(CountActivityExecutionsResponse.getDefaultInstance()));
    }
  }
}
