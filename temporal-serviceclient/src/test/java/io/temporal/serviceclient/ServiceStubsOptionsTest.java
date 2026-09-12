package io.temporal.serviceclient;

import static org.junit.Assert.*;

import io.grpc.Metadata;
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

  @Test
  public void testGrpcCompressionDefaultsToGzip() {
    ServiceStubsOptions options =
        WorkflowServiceStubsOptions.newBuilder()
            .setTarget("localhost:7233")
            .validateAndBuildWithDefaults();

    assertEquals(GrpcCompression.GZIP, options.getGrpcCompression());
  }

  @Test
  public void testGrpcCompressionNonePassesThroughBuilderCopy() {
    ServiceStubsOptions options =
        WorkflowServiceStubsOptions.newBuilder()
            .setTarget("localhost:7233")
            .setGrpcCompression(GrpcCompression.NONE)
            .validateAndBuildWithDefaults();

    assertEquals(GrpcCompression.NONE, options.getGrpcCompression());

    ServiceStubsOptions copied =
        WorkflowServiceStubsOptions.newBuilder(options).validateAndBuildWithDefaults();

    assertEquals(GrpcCompression.NONE, copied.getGrpcCompression());
  }

  @Test
  public void testWorkflowServiceStubsOptionsToStringIncludesInheritedFields() {
    WorkflowServiceStubsOptions options =
        WorkflowServiceStubsOptions.newBuilder()
            .setTarget("localhost:7233")
            .validateAndBuildWithDefaults();

    String rendered = options.toString();

    assertTrue(rendered.startsWith("WorkflowServiceStubsOptions{"));
    // Inherited fields used to be dropped entirely.
    assertTrue(rendered, rendered.contains("target='localhost:7233'"));
    assertTrue(rendered, rendered.contains("enableHttps="));
    assertTrue(rendered, rendered.contains("rpcTimeout="));
    assertTrue(rendered, rendered.contains("grpcCompression="));
    // Fields declared on the subclass are still present.
    assertTrue(rendered, rendered.contains("disableHealthCheck="));
    assertTrue(rendered, rendered.contains("rpcLongPollTimeout="));
    // Inherited fields are inlined, not nested inside a second wrapper.
    assertFalse(rendered, rendered.contains("{ServiceStubsOptions{"));
  }

  @Test
  public void testOperatorServiceStubsOptionsToString() {
    OperatorServiceStubsOptions options =
        OperatorServiceStubsOptions.newBuilder()
            .setTarget("localhost:7233")
            .validateAndBuildWithDefaults();

    String rendered = options.toString();

    assertTrue(rendered.startsWith("OperatorServiceStubsOptions{"));
    assertTrue(rendered, rendered.contains("target='localhost:7233'"));
    assertTrue(rendered, rendered.contains("rpcTimeout="));
  }

  @Test
  public void testCloudServiceStubsOptionsToStringIncludesVersion() {
    CloudServiceStubsOptions options =
        CloudServiceStubsOptions.newBuilder()
            .setTarget("localhost:7233")
            .setVersion("v1")
            .validateAndBuildWithDefaults();

    String rendered = options.toString();

    assertTrue(rendered.startsWith("CloudServiceStubsOptions{"));
    assertTrue(rendered, rendered.contains("target='localhost:7233'"));
    assertTrue(rendered, rendered.contains("version='v1'"));
  }

  @Test
  public void testToStringDoesNotLeakApiKey() {
    WorkflowServiceStubsOptions options =
        WorkflowServiceStubsOptions.newBuilder()
            .setTarget("localhost:7233")
            .addApiKey(() -> "super-secret-api-key")
            .validateAndBuildWithDefaults();

    String rendered = options.toString();

    assertFalse(rendered, rendered.contains("super-secret-api-key"));
    // The fact that an API key was configured is still useful when debugging.
    assertTrue(rendered, rendered.contains("apiKeyProvided=true"));
  }

  @Test
  public void testToStringRendersHeaderNamesWithoutValues() {
    Metadata headers = new Metadata();
    headers.put(
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
        "Bearer super-secret-token");
    headers.put(Metadata.Key.of("x-custom", Metadata.ASCII_STRING_MARSHALLER), "plain-value");

    WorkflowServiceStubsOptions options =
        WorkflowServiceStubsOptions.newBuilder()
            .setTarget("localhost:7233")
            .setHeaders(headers)
            .validateAndBuildWithDefaults();

    String rendered = options.toString();

    // Metadata.toString renders values in the clear, so it must not be embedded directly.
    assertFalse(rendered, rendered.contains("super-secret-token"));
    assertFalse(rendered, rendered.contains("plain-value"));
    // Header names are still reported, which is what makes the output useful for debugging.
    assertTrue(rendered, rendered.contains("authorization"));
    assertTrue(rendered, rendered.contains("x-custom"));
  }
}
