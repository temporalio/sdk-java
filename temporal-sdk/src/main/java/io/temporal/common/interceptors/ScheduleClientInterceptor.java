package io.temporal.common.interceptors;

import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleHandle;
import io.temporal.common.Experimental;

/**
 * Intercepts calls to the {@link ScheduleClient} and {@link ScheduleHandle} related to the
 * lifecycle of a Schedule.
 */
@Experimental
public interface ScheduleClientInterceptor {

  /**
   * Called once during creation of ScheduleClient to create a chain of ScheduleClient Interceptors
   *
   * @param next next schedule client interceptor in the chain of interceptors
   * @return new interceptor that should decorate calls to {@code next}
   */
  ScheduleClientCallsInterceptor scheduleClientCallsInterceptor(
      ScheduleClientCallsInterceptor next);
}
