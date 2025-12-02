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
  public void testTLSCanBeExplicitlyDisabledWithAPIKey() {
    ServiceStubsOptions options =
        WorkflowServiceStubsOptions.newBuilder()
            .setTarget("localhost:7233")
            .addApiKey(() -> "test-api-key")
            .setEnableHttps(false)
            .validateAndBuildWithDefaults();

    assertFalse(options.getEnableHttps());
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
}
