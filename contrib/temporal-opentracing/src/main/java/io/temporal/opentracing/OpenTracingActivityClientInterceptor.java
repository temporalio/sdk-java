package io.temporal.opentracing;

import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import io.temporal.common.interceptors.ActivityClientInterceptorBase;
import io.temporal.opentracing.internal.ContextAccessor;
import io.temporal.opentracing.internal.OpenTracingActivityClientCallsInterceptor;
import io.temporal.opentracing.internal.SpanFactory;

public class OpenTracingActivityClientInterceptor extends ActivityClientInterceptorBase {
  private final OpenTracingOptions options;
  private final SpanFactory spanFactory;
  private final ContextAccessor contextAccessor;

  public OpenTracingActivityClientInterceptor() {
    this(OpenTracingOptions.getDefaultInstance());
  }

  public OpenTracingActivityClientInterceptor(OpenTracingOptions options) {
    this.options = options;
    this.spanFactory = new SpanFactory(options);
    this.contextAccessor = new ContextAccessor(options);
  }

  @Override
  public ActivityClientCallsInterceptor activityClientCallsInterceptor(
      ActivityClientCallsInterceptor next) {
    return new OpenTracingActivityClientCallsInterceptor(
        next, options, spanFactory, contextAccessor);
  }
}
