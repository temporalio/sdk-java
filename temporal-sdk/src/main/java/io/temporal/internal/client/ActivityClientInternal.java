package io.temporal.internal.client;

import io.temporal.common.interceptors.ActivityClientCallsInterceptor;

/**
 * Internal-only view of an {@code ActivityClient} that exposes the configured interceptor chain.
 *
 * <p>Lives in {@code io.temporal.internal.client} so that other internal SDK packages (e.g. {@code
 * io.temporal.nexus}) can route a fully-constructed {@link
 * ActivityClientCallsInterceptor.StartActivityInput} through the chain without bypassing
 * user-registered interceptors or the metrics-tagged scope, and without forcing the concrete impl
 * class to be public.
 */
public interface ActivityClientInternal {
  ActivityClientCallsInterceptor getInvoker();
}
