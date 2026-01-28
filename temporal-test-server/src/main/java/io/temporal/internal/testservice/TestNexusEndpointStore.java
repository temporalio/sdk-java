package io.temporal.internal.testservice;

import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.api.nexus.v1.EndpointSpec;
import java.io.Closeable;
import java.util.List;

public interface TestNexusEndpointStore extends Closeable {

  Endpoint createEndpoint(EndpointSpec spec);

  Endpoint updateEndpoint(String id, long version, EndpointSpec spec);

  void deleteEndpoint(String id, long version);

  Endpoint getEndpoint(String id);

  Endpoint getEndpointByName(String name);

  List<Endpoint> listEndpoints(long pageSize, byte[] nextPageToken, String name);

  void validateEndpointSpec(EndpointSpec spec);

  @Override
  void close();
}
