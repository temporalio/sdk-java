package io.temporal.opentracing.internal;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import io.temporal.common.interceptors.ActivityClientCallsInterceptorBase;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.opentracing.SpanOperationType;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

public class OpenTracingActivityClientCallsInterceptor extends ActivityClientCallsInterceptorBase {
  private final SpanFactory spanFactory;
  private final Tracer tracer;
  private final ContextAccessor contextAccessor;

  public OpenTracingActivityClientCallsInterceptor(
      ActivityClientCallsInterceptor next,
      OpenTracingOptions options,
      SpanFactory spanFactory,
      ContextAccessor contextAccessor) {
    super(next);
    this.spanFactory = spanFactory;
    this.tracer = options.getTracer();
    this.contextAccessor = contextAccessor;
  }

  @Override
  public StartActivityOutput startActivity(StartActivityInput input) {
    Span activityStartSpan =
        contextAccessor.writeSpanContextToHeader(
            () ->
                spanFactory
                    .createStandaloneActivityStartSpan(
                        tracer, input.getActivityType(), input.getOptions().getId())
                    .start(),
            input.getHeader(),
            tracer);
    try (Scope ignored = tracer.scopeManager().activate(activityStartSpan)) {
      return super.startActivity(input);
    } finally {
      activityStartSpan.finish();
    }
  }

  @Override
  public <R> GetActivityResultOutput<R> getActivityResult(GetActivityResultInput<R> input)
      throws TimeoutException {
    Span span =
        spanFactory
            .createStandaloneActivityOperationSpan(
                tracer, SpanOperationType.GET_STANDALONE_ACTIVITY_RESULT, input.getActivityId())
            .start();
    try (Scope ignored = tracer.scopeManager().activate(span)) {
      return super.getActivityResult(input);
    } finally {
      span.finish();
    }
  }

  @Override
  public <R> CompletableFuture<GetActivityResultOutput<R>> getActivityResultAsync(
      GetActivityResultInput<R> input) {
    Span span =
        spanFactory
            .createStandaloneActivityOperationSpan(
                tracer, SpanOperationType.GET_STANDALONE_ACTIVITY_RESULT, input.getActivityId())
            .start();
    try (Scope ignored = tracer.scopeManager().activate(span)) {
      return super.getActivityResultAsync(input)
          .whenComplete(
              (result, throwable) -> {
                span.finish();
              });
    } catch (Throwable t) {
      span.finish();
      throw t;
    }
  }

  @Override
  public DescribeActivityOutput describeActivity(DescribeActivityInput input) {
    Span span =
        spanFactory
            .createStandaloneActivityOperationSpan(
                tracer, SpanOperationType.DESCRIBE_STANDALONE_ACTIVITY, input.getId())
            .start();
    try (Scope ignored = tracer.scopeManager().activate(span)) {
      return super.describeActivity(input);
    } finally {
      span.finish();
    }
  }

  @Override
  public CancelActivityOutput cancelActivity(CancelActivityInput input) {
    Span span =
        spanFactory
            .createStandaloneActivityOperationSpan(
                tracer, SpanOperationType.CANCEL_STANDALONE_ACTIVITY, input.getId())
            .start();
    try (Scope ignored = tracer.scopeManager().activate(span)) {
      return super.cancelActivity(input);
    } finally {
      span.finish();
    }
  }

  @Override
  public TerminateActivityOutput terminateActivity(TerminateActivityInput input) {
    Span span =
        spanFactory
            .createStandaloneActivityOperationSpan(
                tracer, SpanOperationType.TERMINATE_STANDALONE_ACTIVITY, input.getId())
            .start();
    try (Scope ignored = tracer.scopeManager().activate(span)) {
      return super.terminateActivity(input);
    } finally {
      span.finish();
    }
  }

  @Override
  public ListActivitiesOutput listActivities(ListActivitiesInput input) {
    Span span =
        spanFactory
            .createStandaloneActivityQuerySpan(
                tracer, SpanOperationType.LIST_STANDALONE_ACTIVITIES, input.getQuery())
            .start();
    try (Scope ignored = tracer.scopeManager().activate(span)) {
      return super.listActivities(input);
    } finally {
      span.finish();
    }
  }

  @Override
  public CountActivitiesOutput countActivities(CountActivitiesInput input) {
    Span span =
        spanFactory
            .createStandaloneActivityQuerySpan(
                tracer, SpanOperationType.COUNT_STANDALONE_ACTIVITIES, input.getQuery())
            .start();
    try (Scope ignored = tracer.scopeManager().activate(span)) {
      return super.countActivities(input);
    } finally {
      span.finish();
    }
  }
}
