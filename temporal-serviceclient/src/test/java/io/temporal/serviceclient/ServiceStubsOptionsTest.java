package io.temporal.serviceclient;

import static org.junit.Assert.*;

import org.junit.Test;

public class ServiceStubsOptionsTest {

  @Test
  public void testTLSEnabledByDefaultWhenAPIKeyProvided() {
    ServiceStubsOptions options =
        WorkflowServiceStubsOptions.newBuilder()
            .setTarget("localhost:7233")
            .addApiKey(() -> "test-api-key")
            .validateAndBuildWithDefaults();

    assertTrue(options.getEnableHttps());
  }

  @Test
  public void testExplicitTLSDisableBeforeAPIKeyStillDisables() {
    ServiceStubsOptions options =
        WorkflowServiceStubsOptions.newBuilder()
            .setTarget("localhost:7233")
            .setEnableHttps(false)
            .addApiKey(() -> "test-api-key")
            .validateAndBuildWithDefaults();

    // Explicit TLS=false should take precedence regardless of order
    assertFalse(options.getEnableHttps());
  }

  @Test
  public void testExplicitTLSDisableAfterAPIKeyStillDisables() {
    ServiceStubsOptions options =
        WorkflowServiceStubsOptions.newBuilder()
            .setTarget("localhost:7233")
            .addApiKey(() -> "test-api-key")
            .setEnableHttps(false)
            .validateAndBuildWithDefaults();

    // Explicit TLS=false should take precedence regardless of order
    assertFalse(options.getEnableHttps());
  }

  @Test
  public void testTLSDisabledByDefaultWithoutAPIKey() {
    ServiceStubsOptions options =
        WorkflowServiceStubsOptions.newBuilder()
            .setTarget("localhost:7233")
            .validateAndBuildWithDefaults();

    assertFalse(options.getEnableHttps());
  }

  @Test
  public void testExplicitTLSEnableWithoutAPIKey() {
    ServiceStubsOptions options =
        WorkflowServiceStubsOptions.newBuilder()
            .setTarget("localhost:7233")
            .setEnableHttps(true)
            .validateAndBuildWithDefaults();

    assertTrue(options.getEnableHttps());
  }

  @Test
  public void testBuilderFromOptionsPreservesDefaultTLSBehavior() {
    ServiceStubsOptions options1 =
        WorkflowServiceStubsOptions.newBuilder()
            .setTarget("localhost:7233")
            .validateAndBuildWithDefaults();

    assertFalse(options1.getEnableHttps());

    ServiceStubsOptions options2 =
        WorkflowServiceStubsOptions.newBuilder(options1)
            .addApiKey(() -> "test-api-key")
            .validateAndBuildWithDefaults();

    assertTrue(
        "TLS should auto-enable when API key is added to builder from options that had default TLS behavior",
        options2.getEnableHttps());
  }

  @Test
  public void testBuilderFromOptionsWithExplicitTLSDisableStaysDisabled() {
    ServiceStubsOptions options1 =
        WorkflowServiceStubsOptions.newBuilder()
            .setTarget("localhost:7233")
            .setEnableHttps(false)
            .validateAndBuildWithDefaults();

    assertFalse(options1.getEnableHttps());

    ServiceStubsOptions options2 =
        WorkflowServiceStubsOptions.newBuilder(options1)
            .addApiKey(() -> "test-api-key")
            .validateAndBuildWithDefaults();

    assertFalse(
        "TLS should stay disabled when explicitly set to false, even with API key",
        options2.getEnableHttps());
  }

  @Test
  public void testBuilderFromOptionsWithExplicitTLSEnableStaysEnabled() {
    ServiceStubsOptions options1 =
        WorkflowServiceStubsOptions.newBuilder()
            .setTarget("localhost:7233")
            .setEnableHttps(true)
            .validateAndBuildWithDefaults();

    assertTrue(options1.getEnableHttps());

    ServiceStubsOptions options2 =
        WorkflowServiceStubsOptions.newBuilder(options1).validateAndBuildWithDefaults();

    assertTrue("TLS should stay enabled when explicitly set to true", options2.getEnableHttps());
  }

  @Test
  public void testSpringBootStyleAutoTLSWithApiKey() {
    ServiceStubsOptions options1 =
        WorkflowServiceStubsOptions.newBuilder()
            .setTarget("my-namespace.tmprl.cloud:7233")
            .addApiKey(() -> "my-api-key")
            .validateAndBuildWithDefaults();

    assertTrue(
        "TLS should auto-enable when API key is provided without explicit TLS setting",
        options1.getEnableHttps());

    ServiceStubsOptions options2 =
        WorkflowServiceStubsOptions.newBuilder()
            .setTarget("localhost:7233")
            .setEnableHttps(false)
            .addApiKey(() -> "my-api-key")
            .validateAndBuildWithDefaults();

    assertFalse(
        "TLS should stay disabled when explicitly set to false, even with API key",
        options2.getEnableHttps());

    ServiceStubsOptions options3 =
        WorkflowServiceStubsOptions.newBuilder()
            .setTarget("localhost:7233")
            .validateAndBuildWithDefaults();

    assertFalse(
        "TLS should be disabled when no API key and no explicit TLS setting",
        options3.getEnableHttps());
  }
}
