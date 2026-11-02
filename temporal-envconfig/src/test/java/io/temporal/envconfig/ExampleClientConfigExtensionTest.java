package io.temporal.envconfig;

import com.google.common.collect.ImmutableMap;
import io.grpc.Metadata;
import io.temporal.client.WorkflowClient;
import io.temporal.common.SimplePlugin;
import io.temporal.serviceclient.GrpcMetadataProvider;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.serviceclient.WorkflowServiceStubsPlugin;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import javax.annotation.Nonnull;
import org.junit.Assert;
import org.junit.Test;

/** Example of a complete client config extension, from TOML and env to a created client. */
public class ExampleClientConfigExtensionTest {
  static final Metadata.Key<String> TENANT_HEADER =
      Metadata.Key.of("x-tenant", Metadata.ASCII_STRING_MARSHALLER);

  /**
   * Example extension that sends a tenant header on every request. Configured via:
   *
   * <pre>
   * [profile.default.ext.example]
   * tenant = "my-tenant"
   * </pre>
   *
   * or the {@code TEMPORAL_EXT_EXAMPLE_TENANT} env var, which takes precedence.
   */
  static class ExampleExtension implements ClientConfigExtension {
    @Override
    public String getName() {
      return "example";
    }

    @Override
    public WorkflowServiceStubsPlugin getClientPlugin(ClientConfigExtensionRequest request) {
      String tenant = null;
      Map<String, Object> config = request.getProfile().getExt().get(getName());
      if (config != null && config.get("tenant") != null) {
        Object value = config.get("tenant");
        if (!(value instanceof String)) {
          throw new IllegalArgumentException("tenant must be a string");
        }
        tenant = (String) value;
      }
      if (request.getEnv() != null && request.getEnv().containsKey("TEMPORAL_EXT_EXAMPLE_TENANT")) {
        tenant = request.getEnv().get("TEMPORAL_EXT_EXAMPLE_TENANT");
      }
      if (tenant == null || tenant.isEmpty()) {
        return null;
      }
      return new TenantPlugin(tenant);
    }
  }

  /**
   * Plugin returned by {@link ExampleExtension}. Extending {@link SimplePlugin} also makes it a
   * workflow client and worker plugin, so it could customize those too.
   */
  static class TenantPlugin extends SimplePlugin {
    private final String tenant;

    TenantPlugin(String tenant) {
      super("io.temporal.envconfig.example-tenant");
      this.tenant = tenant;
    }

    @Override
    public void configureServiceStubs(@Nonnull WorkflowServiceStubsOptions.Builder builder) {
      Metadata metadata = new Metadata();
      metadata.put(TENANT_HEADER, tenant);
      builder.addGrpcMetadataProvider(() -> metadata);
    }
  }

  @Test
  public void exampleExtension() throws IOException {
    String toml =
        "[profile.default]\n"
            + "address = \"localhost:7233\"\n"
            + "namespace = \"my-namespace\"\n"
            + "\n"
            + "[profile.default.ext.example]\n"
            + "tenant = \"file-tenant\"\n";
    ClientConfigProfile profile =
        ClientConfigProfile.load(
            LoadClientConfigProfileOptions.newBuilder()
                .setConfigFileData(toml.getBytes())
                .setConfigFileStrict(true)
                .setEnvOverrides(Collections.emptyMap())
                .setExtensions(Collections.singletonList(new ExampleExtension()))
                .build());
    Assert.assertEquals("file-tenant", getTenant(profile));

    // Env takes precedence
    profile =
        ClientConfigProfile.load(
            LoadClientConfigProfileOptions.newBuilder()
                .setConfigFileData(toml.getBytes())
                .setEnvOverrides(ImmutableMap.of("TEMPORAL_EXT_EXAMPLE_TENANT", "env-tenant"))
                .setExtensions(Collections.singletonList(new ExampleExtension()))
                .build());
    Assert.assertEquals("env-tenant", getTenant(profile));
  }

  // Creates a client from the profile, and returns the tenant header the client would send
  private static String getTenant(ClientConfigProfile profile) {
    WorkflowServiceStubs stubs =
        WorkflowServiceStubs.newServiceStubs(profile.toWorkflowServiceStubsOptions());
    try {
      WorkflowClient client = WorkflowClient.newInstance(stubs, profile.toWorkflowClientOptions());
      // The plugin is propagated from the service stubs to the client
      Assert.assertEquals(1, client.getOptions().getPlugins().length);
      Assert.assertTrue(
          Arrays.stream(client.getOptions().getPlugins()).anyMatch(p -> p instanceof TenantPlugin));
      String tenant = null;
      for (GrpcMetadataProvider provider : stubs.getOptions().getGrpcMetadataProviders()) {
        String value = provider.getMetadata().get(TENANT_HEADER);
        if (value != null) {
          tenant = value;
        }
      }
      return tenant;
    } finally {
      stubs.shutdownNow();
    }
  }
}
