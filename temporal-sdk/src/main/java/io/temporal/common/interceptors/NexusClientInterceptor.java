package io.temporal.common.interceptors;

import io.temporal.client.NexusClient;
import io.temporal.client.NexusClientOptions;
import io.temporal.common.Experimental;

/**
 * Outer interceptor for {@link NexusClient}. Implementations are registered via {@link
 * NexusClientOptions.Builder#setInterceptors(java.util.List)} and consulted once during client
 * construction to build the chain of {@link NexusClientCallsInterceptor}s that wraps the root
 * invoker.
 */
@Experimental
public interface NexusClientInterceptor {

  /**
   * Called once during {@link NexusClient} construction to build the chain of per-call
   * interceptors.
   *
   * @param next next per-call interceptor in the chain
   * @return new per-call interceptor that decorates calls to {@code next}
   */
  NexusClientCallsInterceptor nexusClientCallsInterceptor(NexusClientCallsInterceptor next);
}
