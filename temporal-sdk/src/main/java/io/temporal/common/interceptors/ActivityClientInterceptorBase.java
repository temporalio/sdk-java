package io.temporal.common.interceptors;

import io.temporal.common.Experimental;

/**
 * Convenience no-op base class for {@link ActivityClientInterceptor} implementations. Override
 * {@link #activityClientCallsInterceptor} to install a custom {@link
 * ActivityClientCallsInterceptor} into the chain.
 */
@Experimental
public class ActivityClientInterceptorBase implements ActivityClientInterceptor {

  @Override
  public ActivityClientCallsInterceptor activityClientCallsInterceptor(
      ActivityClientCallsInterceptor next) {
    return next;
  }
}
