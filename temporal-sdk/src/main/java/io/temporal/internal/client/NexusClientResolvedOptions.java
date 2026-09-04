package io.temporal.internal.client;

import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.NexusClientInterceptor;
import java.util.List;

/** Resolved runtime settings used by the internal Nexus client implementation. */
public final class NexusClientResolvedOptions {

  private final String namespace;
  private final List<NexusClientInterceptor> interceptors;
  private final DataConverter dataConverter;
  private final String identity;

  public NexusClientResolvedOptions(
      String namespace,
      List<NexusClientInterceptor> interceptors,
      DataConverter dataConverter,
      String identity) {
    this.namespace = namespace;
    this.interceptors = interceptors;
    this.dataConverter = dataConverter;
    this.identity = identity;
  }

  public String getNamespace() {
    return namespace;
  }

  public List<NexusClientInterceptor> getInterceptors() {
    return interceptors;
  }

  public DataConverter getDataConverter() {
    return dataConverter;
  }

  public String getIdentity() {
    return identity;
  }
}
