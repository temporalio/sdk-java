package io.temporal.internal.client;

import io.temporal.common.interceptors.ActivityClientCallsInterceptor;

/**
 * Internal-only view of an {@code ActivityClient} that exposes its invocation chain.
 *
 * <p>Lives in {@code io.temporal.internal.client} so that other internal SDK packages (e.g. {@code
 * io.temporal.nexus}) can route a fully-constructed {@link
 * ActivityClientCallsInterceptor.StartActivityInput} through an internal client without forcing the
 * concrete implementation class to be public.
 */
public interface ActivityClientInternal {
  ActivityClientCallsInterceptor getInvoker();
}
