package io.temporal.testing.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Metadata;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowRule;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class ExternalServiceTestConfiguratorTest {

  @Test
  public void configureTestWorkflowRuleFromEnvConfig() {
    Map<String, String> environment = newEnvConfigEnvironment();
    environment.put("USE_EXTERNAL_SERVICE", "true");
    environment.put("TEMPORAL_SERVICE_ADDRESS", "legacy-address:7233");

    TestWorkflowRule rule =
        ExternalServiceTestConfigurator.configure(TestWorkflowRule.newBuilder(), environment)
            .build();
    try {
      assertTrue(rule.isUseExternalService());
      assertEquals(
          "envconfig-address:7233", rule.getWorkflowServiceStubs().getOptions().getTarget());
      assertEquals("envconfig-namespace", rule.getWorkflowClient().getOptions().getNamespace());

      WorkflowServiceStubsOptions stubsOptions = rule.getWorkflowServiceStubs().getOptions();
      assertTrue(stubsOptions.getEnableHttps());
      Metadata metadata = new Metadata();
      stubsOptions
          .getGrpcMetadataProviders()
          .forEach(provider -> metadata.merge(provider.getMetadata()));
      assertEquals(
          "metadata-value",
          metadata.get(Metadata.Key.of("test-header", Metadata.ASCII_STRING_MARSHALLER)));
      assertEquals(
          "Bearer api-key",
          metadata.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)));
    } finally {
      rule.getTestEnvironment().close();
    }
  }

  @Test
  public void preserveLegacyAndLocalModes() {
    Map<String, String> environment = new HashMap<>();
    TestEnvironmentOptions localOptions =
        ExternalServiceTestConfigurator.configure(TestEnvironmentOptions.newBuilder(), environment)
            .build();
    assertFalse(localOptions.isUseExternalService());

    environment.put("USE_EXTERNAL_SERVICE", "true");
    environment.put("TEMPORAL_SERVICE_ADDRESS", "legacy-address:7233");
    TestEnvironmentOptions externalOptions =
        ExternalServiceTestConfigurator.configure(TestEnvironmentOptions.newBuilder(), environment)
            .build();
    assertTrue(externalOptions.isUseExternalService());
    assertEquals("legacy-address:7233", externalOptions.getTarget());
  }

  @Test
  public void preserveDevServerProfilePrecedence() {
    Map<String, String> environment = new HashMap<>();
    environment.put("USE_EXTERNAL_SERVICE", "true");
    environment.put("TEMPORAL_SERVICE_ADDRESS", "legacy-address:7233");

    TestEnvironmentOptions devServerOptions =
        ExternalServiceTestConfigurator.configure(
                TestEnvironmentOptions.newBuilder(), environment, "dev-server-address:7233")
            .build();
    assertTrue(devServerOptions.isUseExternalService());
    assertEquals("dev-server-address:7233", devServerOptions.getTarget());

    TestEnvironmentOptions envConfigOptions =
        ExternalServiceTestConfigurator.configure(
                TestEnvironmentOptions.newBuilder(),
                newEnvConfigEnvironment(),
                "dev-server-address:7233")
            .build();
    assertEquals("envconfig-address:7233", envConfigOptions.getTarget());
    assertEquals("envconfig-namespace", envConfigOptions.getWorkflowClientOptions().getNamespace());
  }

  @Test
  public void requireAddressAndNamespaceInEnvConfigMode() {
    Map<String, String> environment = new HashMap<>();
    environment.put(ExternalServiceTestConfigurator.TEMPORAL_TEST_ENV_CONFIG_SERVER, "true");
    environment.put("TEMPORAL_CONFIG_FILE", nonExistentConfigFile());
    environment.put("TEMPORAL_ADDRESS", "envconfig-address:7233");

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () ->
                ExternalServiceTestConfigurator.configure(
                    TestEnvironmentOptions.newBuilder(), environment));
    assertEquals("Envconfig test harness requires a Temporal namespace.", exception.getMessage());
  }

  private static Map<String, String> newEnvConfigEnvironment() {
    Map<String, String> environment = new HashMap<>();
    environment.put(ExternalServiceTestConfigurator.TEMPORAL_TEST_ENV_CONFIG_SERVER, "true");
    environment.put("TEMPORAL_CONFIG_FILE", nonExistentConfigFile());
    environment.put("TEMPORAL_ADDRESS", "envconfig-address:7233");
    environment.put("TEMPORAL_NAMESPACE", "envconfig-namespace");
    environment.put("TEMPORAL_API_KEY", "api-key");
    environment.put("TEMPORAL_GRPC_META_TEST_HEADER", "metadata-value");
    return environment;
  }

  private static String nonExistentConfigFile() {
    return new File("build/non-existent-envconfig-" + UUID.randomUUID()).getAbsolutePath();
  }
}
