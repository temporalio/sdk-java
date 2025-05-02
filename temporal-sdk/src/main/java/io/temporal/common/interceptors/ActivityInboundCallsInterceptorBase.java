package io.temporal.common.interceptors;

import io.temporal.activity.ActivityExecutionContext;

public class ActivityInboundCallsInterceptorBase implements ActivityInboundCallsInterceptor {
  private final ActivityInboundCallsInterceptor next;

  public ActivityInboundCallsInterceptorBase(ActivityInboundCallsInterceptor next) {
    this.next = next;
  }

  @Override
  public void init(ActivityExecutionContext context) {
    next.init(context);
  }

  @Override
  public ActivityOutput execute(ActivityInput input) {
    return next.execute(input);
  }
}
