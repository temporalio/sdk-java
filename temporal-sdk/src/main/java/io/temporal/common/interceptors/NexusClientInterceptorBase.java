package io.temporal.common.interceptors;

import io.temporal.common.Experimental;

/** Convenience base class for {@link NexusClientInterceptor} implementations. */
@Experimental
public class NexusClientInterceptorBase implements NexusClientInterceptor {

  @Override
  public NexusClientCallsInterceptor nexusClientCallsInterceptor(NexusClientCallsInterceptor next) {
    return next;
  }
}
