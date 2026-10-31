package io.temporal.testing.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Metadata;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.envconfig.ClientConfigProfile;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowRule;
import org.junit.jupiter.api.Test;

public class ExternalServiceTestConfiguratorTest {

  @Test
  public void configureTestWorkflowRuleFromEnvConfig() {
    TestWorkflowRule rule =
        ExternalServiceTestConfigurator.configureFromEnvConfig(
                TestWorkflowRule.newBuilder(), newProfile())
            .build();
    try {
      assertEquals(
          "envconfig-address:7233", rule.getWorkflowServiceStubs().getOptions().getTarget());
      assertEquals("envconfig-namespace", rule.getWorkflowClient().getOptions().getNamespace());
    } finally {
      rule.getTestEnvironment().close();
    }
  }

  @Test
  public void configureTestEnvironmentFromEnvConfig() {
    TestEnvironmentOptions options =
        ExternalServiceTestConfigurator.configureFromEnvConfig(
                TestEnvironmentOptions.newBuilder(), newProfile())
            .build();

    assertTrue(options.isUseExternalService());
    assertEquals("envconfig-address:7233", options.getTarget());
    assertEquals("envconfig-address:7233", options.getWorkflowServiceStubsOptions().getTarget());
    assertEquals("envconfig-namespace", options.getWorkflowClientOptions().getNamespace());
    assertTrue(options.getWorkflowServiceStubsOptions().getEnableHttps());

    Metadata metadata = metadata(options.getWorkflowServiceStubsOptions());
    assertEquals(
        "metadata-value",
        metadata.get(Metadata.Key.of("test-header", Metadata.ASCII_STRING_MARSHALLER)));
    assertEquals(
        "Bearer api-key",
        metadata.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)));
  }

  @Test
  public void configureTestWorkflowRulePreservesClientOptions() {
    DataConverter converter = DefaultDataConverter.newDefaultInstance();
    TestWorkflowRule rule =
        ExternalServiceTestConfigurator.configureFromEnvConfig(
                TestWorkflowRule.newBuilder().setWorkflowClientOptions(clientOptions(converter)),
                newProfile())
            .build();
    try {
      WorkflowClientOptions options = rule.getWorkflowClient().getOptions();
      assertSame(converter, options.getDataConverter());
      assertEquals("test-identity", options.getIdentity());
      assertEquals("envconfig-namespace", options.getNamespace());
    } finally {
      rule.getTestEnvironment().close();
    }
  }

  @Test
  public void configureTestEnvironmentPreservesClientOptions() {
    DataConverter converter = DefaultDataConverter.newDefaultInstance();
    TestEnvironmentOptions options =
        ExternalServiceTestConfigurator.configureFromEnvConfig(
                TestEnvironmentOptions.newBuilder()
                    .setWorkflowClientOptions(clientOptions(converter)),
                newProfile())
            .build();

    assertSame(converter, options.getWorkflowClientOptions().getDataConverter());
    assertEquals("test-identity", options.getWorkflowClientOptions().getIdentity());
    assertEquals("envconfig-namespace", options.getWorkflowClientOptions().getNamespace());
  }

  private static WorkflowClientOptions clientOptions(DataConverter converter) {
    return WorkflowClientOptions.newBuilder()
        .setNamespace("test-namespace")
        .setIdentity("test-identity")
        .setDataConverter(converter)
        .build();
  }

  @Test
  public void requireAddressAndNamespaceInEnvConfigMode() {
    ClientConfigProfile missingAddress =
        ClientConfigProfile.newBuilder().setNamespace("envconfig-namespace").build();
    IllegalStateException missingAddressException =
        assertThrows(
            IllegalStateException.class,
            () ->
                ExternalServiceTestConfigurator.configureFromEnvConfig(
                    TestEnvironmentOptions.newBuilder(), missingAddress));
    assertEquals(
        "Envconfig test harness requires a Temporal server address.",
        missingAddressException.getMessage());

    ClientConfigProfile missingNamespace =
        ClientConfigProfile.newBuilder().setAddress("envconfig-address:7233").build();
    IllegalStateException missingNamespaceException =
        assertThrows(
            IllegalStateException.class,
            () ->
                ExternalServiceTestConfigurator.configureFromEnvConfig(
                    TestEnvironmentOptions.newBuilder(), missingNamespace));
    assertEquals(
        "Envconfig test harness requires a Temporal namespace.",
        missingNamespaceException.getMessage());
  }

  private static ClientConfigProfile newProfile() {
    Metadata metadata = new Metadata();
    metadata.put(
        Metadata.Key.of("test-header", Metadata.ASCII_STRING_MARSHALLER), "metadata-value");
    return ClientConfigProfile.newBuilder()
        .setAddress("envconfig-address:7233")
        .setNamespace("envconfig-namespace")
        .setApiKey("api-key")
        .setMetadata(metadata)
        .build();
  }

  private static Metadata metadata(WorkflowServiceStubsOptions options) {
    Metadata metadata = new Metadata();
    options.getGrpcMetadataProviders().forEach(provider -> metadata.merge(provider.getMetadata()));
    return metadata;
  }
}
