package io.temporal.common.interceptors;

/**
 * Convenience base class for {@link NexusClientInterceptor} implementations.
 *
 * <p>Standalone Nexus Operations are in Public Preview. We aim to preserve backward compatibility
 * and avoid breaking changes.
 */
public class NexusClientInterceptorBase implements NexusClientInterceptor {

  @Override
  public NexusClientCallsInterceptor nexusClientCallsInterceptor(NexusClientCallsInterceptor next) {
    return next;
  }
}
