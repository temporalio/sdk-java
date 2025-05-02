package io.temporal.testserver;

import io.temporal.internal.testservice.TestServicesStarter;

public final class TestServicesStarterAccessor {
  public static TestServicesStarter getStarter(TestServer.InProcessTestServer inProcessTestServer) {
    return inProcessTestServer.getStarter();
  }
}
