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

/**
 * Unit tests for {@link OpenTracingActivityClientCallsInterceptor}. Verifies that each intercepted
 * method creates a span with the expected operation name and tags. Uses a stub next-interceptor so
 * no server is required.
 */
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
    assertEquals("StartStandaloneActivity:MyActivity", span.operationName());
    assertEquals("act-123", span.tags().get("activityId"));
    assertFalse("Trace context should be propagated into header", header.getValues().isEmpty());
  }

  @Test
  public void testGetActivityResultCreatesSpan() throws TimeoutException {
    ActivityClientCallsInterceptor.GetActivityResultInput<String> input =
        new ActivityClientCallsInterceptor.GetActivityResultInput<>("act-456", null, String.class);

    interceptor.getActivityResult(input);

    List<MockSpan> spans = mockTracer.finishedSpans();
    assertEquals(1, spans.size());
    MockSpan span = spans.get(0);
    assertEquals("GetStandaloneActivityResult:StandaloneActivity", span.operationName());
    assertEquals("act-456", span.tags().get("activityId"));
  }

  @Test
  public void testGetActivityResultAsyncCreatesSpan() throws Exception {
    ActivityClientCallsInterceptor.GetActivityResultInput<String> input =
        new ActivityClientCallsInterceptor.GetActivityResultInput<>("act-789", null, String.class);

    CompletableFuture<ActivityClientCallsInterceptor.GetActivityResultOutput<String>> future =
        interceptor.getActivityResultAsync(input);
    future.get();

    List<MockSpan> spans = mockTracer.finishedSpans();
    assertEquals(1, spans.size());
    MockSpan span = spans.get(0);
    assertEquals("GetStandaloneActivityResult:StandaloneActivity", span.operationName());
    assertEquals("act-789", span.tags().get("activityId"));
  }

  @Test
  public void testGetActivityResultAsyncFinishesSpanWhenNextThrowsSynchronously() {
    OpenTracingActivityClientCallsInterceptor throwingInterceptor =
        new OpenTracingActivityClientCallsInterceptor(
            new SynchronouslyThrowingActivityClientCallsInterceptor(),
            otOptions,
            new SpanFactory(otOptions),
            new ContextAccessor(otOptions));
    ActivityClientCallsInterceptor.GetActivityResultInput<String> input =
        new ActivityClientCallsInterceptor.GetActivityResultInput<>(
            "act-throws", null, String.class);

    try {
      throwingInterceptor.getActivityResultAsync(input);
      fail("Expected getActivityResultAsync to throw");
    } catch (IllegalStateException expected) {
      assertEquals("sync failure", expected.getMessage());
    }

    List<MockSpan> spans = mockTracer.finishedSpans();
    assertEquals(1, spans.size());
    MockSpan span = spans.get(0);
    assertEquals("GetStandaloneActivityResult:StandaloneActivity", span.operationName());
    assertEquals("act-throws", span.tags().get("activityId"));
  }

  @Test
  public void testDescribeActivityCreatesSpan() {
    ActivityClientCallsInterceptor.DescribeActivityInput input =
        new ActivityClientCallsInterceptor.DescribeActivityInput("act-desc", null);

    interceptor.describeActivity(input);

    List<MockSpan> spans = mockTracer.finishedSpans();
    assertEquals(1, spans.size());
    MockSpan span = spans.get(0);
    assertEquals("DescribeStandaloneActivity:StandaloneActivity", span.operationName());
    assertEquals("act-desc", span.tags().get("activityId"));
  }

  @Test
  public void testCancelActivityCreatesSpan() {
    ActivityClientCallsInterceptor.CancelActivityInput input =
        new ActivityClientCallsInterceptor.CancelActivityInput("act-cancel", null, "reason");

    interceptor.cancelActivity(input);

    List<MockSpan> spans = mockTracer.finishedSpans();
    assertEquals(1, spans.size());
    MockSpan span = spans.get(0);
    assertEquals("CancelStandaloneActivity:StandaloneActivity", span.operationName());
    assertEquals("act-cancel", span.tags().get("activityId"));
  }

  @Test
  public void testTerminateActivityCreatesSpan() {
    ActivityClientCallsInterceptor.TerminateActivityInput input =
        new ActivityClientCallsInterceptor.TerminateActivityInput("act-term", null, "reason");

    interceptor.terminateActivity(input);

    List<MockSpan> spans = mockTracer.finishedSpans();
    assertEquals(1, spans.size());
    MockSpan span = spans.get(0);
    assertEquals("TerminateStandaloneActivity:StandaloneActivity", span.operationName());
    assertEquals("act-term", span.tags().get("activityId"));
  }

  @Test
  public void testListActivitiesCreatesSpan() {
    ActivityClientCallsInterceptor.ListActivitiesInput input =
        new ActivityClientCallsInterceptor.ListActivitiesInput("TaskQueue = 'tq'");

    interceptor.listActivities(input);

    List<MockSpan> spans = mockTracer.finishedSpans();
    assertEquals(1, spans.size());
    MockSpan span = spans.get(0);
    assertEquals("ListStandaloneActivities:TaskQueue = 'tq'", span.operationName());
    assertNull(span.tags().get("activityId"));
  }

  @Test
  public void testCountActivitiesCreatesSpan() {
    ActivityClientCallsInterceptor.CountActivitiesInput input =
        new ActivityClientCallsInterceptor.CountActivitiesInput("TaskQueue = 'tq'");

    interceptor.countActivities(input);

    List<MockSpan> spans = mockTracer.finishedSpans();
    assertEquals(1, spans.size());
    MockSpan span = spans.get(0);
    assertEquals("CountStandaloneActivities:TaskQueue = 'tq'", span.operationName());
    assertNull(span.tags().get("activityId"));
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
    assertEquals("StartStandaloneActivity:MyActivity", activitySpan.operationName());
    assertEquals(parentSpan.context().spanId(), activitySpan.parentId());
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

  private static class SynchronouslyThrowingActivityClientCallsInterceptor
      extends ActivityClientCallsInterceptorBase {

    SynchronouslyThrowingActivityClientCallsInterceptor() {
      super(null);
    }

    @Override
    public <R> CompletableFuture<GetActivityResultOutput<R>> getActivityResultAsync(
        GetActivityResultInput<R> input) {
      throw new IllegalStateException("sync failure");
    }
  }
}
