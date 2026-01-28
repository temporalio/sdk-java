package io.temporal.common.interceptors;

import io.temporal.common.Experimental;

/** Convenience base class for ScheduleClientInterceptor implementations. */
@Experimental
public class ScheduleClientInterceptorBase implements ScheduleClientInterceptor {

  @Override
  public ScheduleClientCallsInterceptor scheduleClientCallsInterceptor(
      ScheduleClientCallsInterceptor next) {
    return next;
  }
}
