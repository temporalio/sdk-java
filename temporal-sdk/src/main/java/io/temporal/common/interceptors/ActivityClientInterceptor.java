package io.temporal.common.interceptors;

import io.temporal.common.Experimental;

/** Interceptor for {@link io.temporal.client.ActivityClient} lifecycle calls. */
@Experimental
public interface ActivityClientInterceptor {
  /**
   * Called once during creation of ActivityClient to create a chain of ActivityClient calls
   * interceptors.
   *
   * @param next next interceptor in the chain
   * @return new interceptor that should decorate calls to {@code next}
   */
  default ActivityClientCallsInterceptor activityClientCallsInterceptor(
      ActivityClientCallsInterceptor next) {
    return next;
  }
}
