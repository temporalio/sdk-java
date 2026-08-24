package io.temporal.testing.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Metadata;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowRule;
import java.io.File;
import java.time.Duration;
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
  public void preserveTestOptionsWhenApplyingEnvConfigConnection() {
    Map<String, String> environment = newEnvConfigEnvironment();
    Metadata.Key<String> customHeader =
        Metadata.Key.of("custom-option-header", Metadata.ASCII_STRING_MARSHALLER);
    Metadata.Key<String> authorizationHeader =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    Metadata.Key<String> profileHeader =
        Metadata.Key.of("test-header", Metadata.ASCII_STRING_MARSHALLER);
    Metadata.Key<String> fixedHeader =
        Metadata.Key.of("custom-fixed-header", Metadata.ASCII_STRING_MARSHALLER);
    Metadata fixedHeaders = new Metadata();
    fixedHeaders.put(fixedHeader, "custom-fixed-value");
    fixedHeaders.put(authorizationHeader, "Bearer stale-fixed-api-key");
    fixedHeaders.put(profileHeader, "stale-fixed-metadata-value");
    WorkflowServiceStubsOptions serviceOptions =
        WorkflowServiceStubsOptions.newBuilder()
            .setRpcTimeout(Duration.ofSeconds(17))
            .setHeaders(fixedHeaders)
            .addGrpcMetadataProvider(
                () -> {
                  Metadata metadata = new Metadata();
                  metadata.put(customHeader, "custom-option-value");
                  metadata.put(authorizationHeader, "Bearer stale-api-key");
                  metadata.put(profileHeader, "stale-metadata-value");
                  return metadata;
                })
            .build();

    WorkflowServiceStubsOptions configuredServiceOptions =
        ExternalServiceTestConfigurator.configure(serviceOptions, environment);
    assertEquals("envconfig-address:7233", configuredServiceOptions.getTarget());
    assertEquals(Duration.ofSeconds(17), configuredServiceOptions.getRpcTimeout());
    assertTrue(configuredServiceOptions.getEnableHttps());
    Metadata metadata = new Metadata();
    configuredServiceOptions
        .getGrpcMetadataProviders()
        .forEach(provider -> metadata.merge(provider.getMetadata()));
    assertEquals("custom-option-value", metadata.get(customHeader));
    assertEquals("custom-fixed-value", metadata.get(fixedHeader));
    assertEquals("metadata-value", metadata.get(profileHeader));
    assertEquals("Bearer api-key", metadata.get(authorizationHeader));
    int authorizationValues = 0;
    for (String ignored : metadata.getAll(authorizationHeader)) {
      authorizationValues++;
    }
    assertEquals(1, authorizationValues);

    WorkflowClientOptions clientOptions =
        WorkflowClientOptions.newBuilder()
            .setNamespace("test-option-namespace")
            .setIdentity("test-option-identity")
            .build();
    WorkflowClientOptions configuredClientOptions =
        ExternalServiceTestConfigurator.configure(clientOptions, environment);
    assertEquals("envconfig-namespace", configuredClientOptions.getNamespace());
    assertEquals("test-option-identity", configuredClientOptions.getIdentity());
  }

  @Test
  public void sdkRuleReappliesEnvConfigConnectionAfterTestCustomization() {
    Map<String, String> environment = newEnvConfigEnvironment();
    SDKTestWorkflowRule rule =
        new SDKTestWorkflowRule.Builder(environment, null)
            .setUseExternalService(false)
            .setTarget("test-option-address:7233")
            .setNamespace("test-option-namespace")
            .setWorkflowServiceStubsOptions(
                WorkflowServiceStubsOptions.newBuilder()
                    .setRpcTimeout(Duration.ofSeconds(17))
                    .build())
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder()
                    .setNamespace("test-option-namespace")
                    .setIdentity("test-option-identity")
                    .build())
            .build();
    try {
      assertEquals(
          "envconfig-address:7233", rule.getWorkflowServiceStubs().getOptions().getTarget());
      assertEquals(
          Duration.ofSeconds(17), rule.getWorkflowServiceStubs().getOptions().getRpcTimeout());
      assertEquals("envconfig-namespace", rule.getWorkflowClient().getOptions().getNamespace());
      assertEquals("test-option-identity", rule.getWorkflowClient().getOptions().getIdentity());
    } finally {
      rule.getTestEnvironment().close();
    }
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
