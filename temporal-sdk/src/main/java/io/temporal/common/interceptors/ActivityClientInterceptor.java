package io.temporal.common.interceptors;

import io.temporal.common.Experimental;

/**
 * Registered on {@link io.temporal.client.ActivityClientOptions} to intercept calls made by an
 * {@link io.temporal.client.ActivityClient}.
 *
 * <p>Prefer extending {@link ActivityClientInterceptorBase} and overriding only the methods you
 * need instead of implementing this interface directly.
 */
@Experimental
public interface ActivityClientInterceptor {

  /**
   * Called once during creation of an {@link io.temporal.client.ActivityClient} to create a chain
   * of {@link ActivityClientCallsInterceptor} instances.
   *
   * @param next next activity client interceptor in the chain of interceptors
   * @return a new {@link ActivityClientCallsInterceptor} that decorates calls to {@code next}
   */
  ActivityClientCallsInterceptor activityClientCallsInterceptor(
      ActivityClientCallsInterceptor next);
}
