package io.temporal.opentracing;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opentracing.Span;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.util.ThreadLocalScopeManager;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInfo;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor;
import io.temporal.common.interceptors.Header;
import io.temporal.opentracing.internal.ContextAccessor;
import io.temporal.opentracing.internal.OpenTracingActivityInboundCallsInterceptor;
import io.temporal.opentracing.internal.SpanFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for standalone activity tracing on the worker side. */
public class StandaloneActivityWorkerTracingTest {

  private final MockTracer mockTracer =
      new MockTracer(new ThreadLocalScopeManager(), MockTracer.Propagator.TEXT_MAP);

  private final OpenTracingOptions otOptions =
      OpenTracingOptions.newBuilder().setTracer(mockTracer).build();

  private final SpanFactory spanFactory = new SpanFactory(otOptions);
  private final ContextAccessor contextAccessor = new ContextAccessor(otOptions);

  private OpenTracingActivityInboundCallsInterceptor interceptor;

  @Before
  public void setUp() {
    mockTracer.reset();
    interceptor =
        new OpenTracingActivityInboundCallsInterceptor(
            new StubActivityInboundCallsInterceptor(), otOptions, spanFactory, contextAccessor);
  }

  @After
  public void tearDown() {
    mockTracer.reset();
  }

  @Test
  public void testStandaloneActivityRunCreatesSpanWithActivityId() {
    Header header = Header.empty();
    Span activityStartSpan =
        contextAccessor.writeSpanContextToHeader(
            () ->
                spanFactory
                    .createStandaloneActivityStartSpan(
                        mockTracer, "MyStandaloneActivity", "act-run")
                    .start(),
            header,
            mockTracer);
    activityStartSpan.finish();

    ActivityExecutionContext executionContext = mock(ActivityExecutionContext.class);
    ActivityInfo activityInfo = mock(ActivityInfo.class);
    when(activityInfo.isInWorkflow()).thenReturn(false);
    when(activityInfo.getActivityType()).thenReturn("MyStandaloneActivity");
    when(activityInfo.getActivityId()).thenReturn("act-run");
    when(executionContext.getInfo()).thenReturn(activityInfo);

    interceptor.init(executionContext);
    interceptor.execute(new ActivityInboundCallsInterceptor.ActivityInput(header, new Object[0]));

    OpenTracingSpansHelper spansHelper = new OpenTracingSpansHelper(mockTracer.finishedSpans());
    MockSpan startSpan =
        spansHelper.getSpanByOperationName("StartStandaloneActivity:MyStandaloneActivity");
    MockSpan runSpan =
        spansHelper.getSpanByOperationName("RunStandaloneActivity:MyStandaloneActivity");
    assertEquals("act-run", runSpan.tags().get("activityId"));
    assertEquals(startSpan.context().spanId(), runSpan.parentId());
  }

  private static class StubActivityInboundCallsInterceptor
      implements ActivityInboundCallsInterceptor {
    @Override
    public void init(ActivityExecutionContext context) {}

    @Override
    public ActivityOutput execute(ActivityInput input) {
      return new ActivityOutput(null);
    }
  }
}
