package io.temporal.common.interceptors;

public interface ActivityInterceptor {
  ActivityInboundCallsInterceptor interceptActivity(ActivityInboundCallsInterceptor next);
}
