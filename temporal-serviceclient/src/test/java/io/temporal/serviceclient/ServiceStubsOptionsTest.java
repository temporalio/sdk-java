package io.temporal.serviceclient;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
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
  public void testTLSNotAutoEnabledWhenSslContextProvided() {
    // When user provides custom sslContext, they're handling TLS themselves
    // so enableHttps should not be auto-enabled
    SslContext sslContext = mock(SslContext.class);
    ServiceStubsOptions options =
        WorkflowServiceStubsOptions.newBuilder()
            .setTarget("localhost:7233")
            .addApiKey(() -> "test-api-key")
            .setSslContext(sslContext)
            .validateAndBuildWithDefaults();

    // enableHttps stays false because sslContext handles TLS
    assertFalse(options.getEnableHttps());
    assertNotNull(options.getSslContext());
  }

  @Test
  public void testTLSNotAutoEnabledWhenCustomChannelProvided() {
    // When user provides custom channel, they're managing connection themselves
    // so enableHttps should not be auto-enabled
    ManagedChannel channel = mock(ManagedChannel.class);
    ServiceStubsOptions options =
        WorkflowServiceStubsOptions.newBuilder()
            .setChannel(channel)
            .addApiKey(() -> "test-api-key")
            .validateAndBuildWithDefaults();

    assertFalse(options.getEnableHttps());
  }
}
