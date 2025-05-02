package io.temporal.internal.common;

import io.temporal.serviceclient.ServiceStubsOptions;
import io.temporal.serviceclient.SimpleSslContextBuilder;
import javax.net.ssl.SSLException;

/**
 * This class provides shading-safe utils that don't return classes that we relocate during shading
 * to the caller. These tools help modules like spring boot stay not shaded and still be compatible
 * with our shaded artifact
 */
public final class ShadingHelpers {
  private ShadingHelpers() {}

  public static void buildSslContextAndPublishIntoStubOptions(
      SimpleSslContextBuilder sslContextBuilder, ServiceStubsOptions.Builder<?> optionsBuilder)
      throws SSLException {
    optionsBuilder.setSslContext(sslContextBuilder.build());
  }
}
