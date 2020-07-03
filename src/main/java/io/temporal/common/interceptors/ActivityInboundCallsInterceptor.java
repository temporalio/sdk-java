package io.temporal.common.interceptors;

import io.temporal.activity.ActivityExecutionContext;

public interface ActivityInboundCallsInterceptor {
  void init(ActivityExecutionContext context);

  Object execute(Object[] arguments);
}
