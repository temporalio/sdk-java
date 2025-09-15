package io.temporal.envconfig;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import io.grpc.Metadata;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Test;

public class ClientConfigProfileTest {
  @Test
  public void loadProfileApplyEnv() throws IOException {
    String toml =
        "[profile.foo]\n"
            + "address = \"my-address\"\n"
            + "namespace = \"my-namespace\"\n"
            + "api_key = \"my-api-key\"\n"
            + "grpc_meta = { some-heAder1 = \"some-value1\", some-header2 = \"some-value2\", some_heaDer3 = \"some-value3\" }\n"
            + "some_future_key = \"some future value not handled\"\n"
            + "\n"
            + "[profile.foo.tls]\n"
            + "disabled = true\n"
            + "client_cert_path = \"my-client-cert-path\"\n"
            + "client_cert_data = \"my-client-cert-data\"\n"
            + "client_key_path = \"my-client-key-path\"\n"
            + "client_key_data = \"my-client-key-data\"\n"
            + "server_ca_cert_path = \"my-server-ca-cert-path\"\n"
            + "server_ca_cert_data = \"my-server-ca-cert-data\"\n"
            + "server_name = \"my-server-name\"\n"
            + "disable_host_verification = true";
    ClientConfigProfile profile =
        ClientConfigProfile.load(
            LoadClientConfigProfileOptions.newBuilder()
                .setConfigFileData(toml.getBytes())
                .setConfigFileProfile("foo")
                .setEnvOverrides(Collections.emptyMap())
                .build());
    Assert.assertEquals("my-address", profile.getAddress());
    Assert.assertEquals("my-namespace", profile.getNamespace());
    Assert.assertEquals("my-api-key", profile.getApiKey());
    Assert.assertEquals(3, profile.getMetadata().keys().size());
    Assert.assertNotNull(profile.getTls());
    Assert.assertEquals(
        "some-value1",
        profile
            .getMetadata()
            .get(Metadata.Key.of("some-header1", Metadata.ASCII_STRING_MARSHALLER)));
    Assert.assertEquals(
        "some-value2",
        profile
            .getMetadata()
            .get(Metadata.Key.of("some-header2", Metadata.ASCII_STRING_MARSHALLER)));
    Assert.assertEquals(
        "some-value3",
        profile
            .getMetadata()
            .get(Metadata.Key.of("some-header3", Metadata.ASCII_STRING_MARSHALLER)));
    // Verify the correct options are generated
    WorkflowServiceStubsOptions serviceStubsOptions = profile.toWorkflowServiceStubsOptions();
    Assert.assertEquals("my-address", serviceStubsOptions.getTarget());

    WorkflowClientOptions clientOptions = profile.toWorkflowClientOptions();
    Assert.assertEquals(
        WorkflowClientOptions.newBuilder().setNamespace("my-namespace").build(), clientOptions);
    // With env
    profile =
        ClientConfigProfile.load(
            LoadClientConfigProfileOptions.newBuilder()
                .setConfigFileData(toml.getBytes())
                .setEnvOverrides(
                    new ImmutableMap.Builder<String, String>()
                        .put("TEMPORAL_PROFILE", "foo")
                        .put("TEMPORAL_ADDRESS", "my-address-new")
                        .put("TEMPORAL_NAMESPACE", "my-namespace-new")
                        .put("TEMPORAL_API_KEY", "my-api-key-new")
                        .put("TEMPORAL_TLS", "false")
                        .put("TEMPORAL_TLS_CLIENT_CERT_PATH", "my-client-cert-path-new")
                        .put("TEMPORAL_TLS_CLIENT_CERT_DATA", "my-client-cert-data-new")
                        .put("TEMPORAL_TLS_CLIENT_KEY_PATH", "my-client-key-path-new")
                        .put("TEMPORAL_TLS_CLIENT_KEY_DATA", "my-client-key-data-new")
                        .put("TEMPORAL_TLS_SERVER_CA_CERT_PATH", "my-server-ca-cert-path-new")
                        .put("TEMPORAL_TLS_SERVER_CA_CERT_DATA", "my-server-ca-cert-data-new")
                        .put("TEMPORAL_TLS_SERVER_NAME", "my-server-name-new")
                        .put("TEMPORAL_TLS_DISABLE_HOST_VERIFICATION", "false")
                        .put("TEMPORAL_GRPC_META_SOME_HEADER2", "some-value2-new")
                        .put("TEMPORAL_GRPC_META_SOME_HEADER3", "")
                        .put("TEMPORAL_GRPC_META_SOME_HEADER4", "some-value4-new")
                        .build())
                .build());
    Assert.assertEquals("my-address-new", profile.getAddress());
    Assert.assertEquals("my-namespace-new", profile.getNamespace());
    Assert.assertEquals("my-api-key-new", profile.getApiKey());
    Assert.assertEquals(3, profile.getMetadata().keys().size());
    Assert.assertNotNull(profile.getTls());
    Assert.assertEquals(
        "some-value1",
        profile
            .getMetadata()
            .get(Metadata.Key.of("some-header1", Metadata.ASCII_STRING_MARSHALLER)));
    Assert.assertEquals(
        "some-value2-new",
        profile
            .getMetadata()
            .get(Metadata.Key.of("some-header2", Metadata.ASCII_STRING_MARSHALLER)));
    Assert.assertNull(
        profile
            .getMetadata()
            .get(Metadata.Key.of("some-header3", Metadata.ASCII_STRING_MARSHALLER)));
    Assert.assertEquals(
        "some-value4-new",
        profile
            .getMetadata()
            .get(Metadata.Key.of("some-header4", Metadata.ASCII_STRING_MARSHALLER)));
  }

  @Test
  public void loadClientConfigProfileFile() throws IOException {
    // Put some data in temp file and set the env var to use that file
    File temp = File.createTempFile("envConfigTest", "");
    temp.deleteOnExit();
    Files.asCharSink(temp, Charsets.UTF_8)
        .write(
            "[profile.default]\n" + "address = \"my-address\"\n" + "namespace = \"my-namespace\"");
    // Explicitly set
    ClientConfigProfile profile =
        ClientConfigProfile.load(
            LoadClientConfigProfileOptions.newBuilder()
                .setConfigFilePath(temp.getAbsolutePath())
                .setEnvOverrides(Collections.emptyMap())
                .build());
    Assert.assertEquals("my-address", profile.getAddress());
    Assert.assertEquals("my-namespace", profile.getNamespace());
    // From env
    profile =
        ClientConfigProfile.load(
            LoadClientConfigProfileOptions.newBuilder()
                .setEnvOverrides(
                    Collections.singletonMap("TEMPORAL_CONFIG_FILE", temp.getAbsolutePath()))
                .build());
    Assert.assertEquals("my-address", profile.getAddress());
    Assert.assertEquals("my-namespace", profile.getNamespace());
  }

  @Test
  public void loadClientOptionsAPIKeyTLS() throws IOException {
    // Since API key is present, TLS defaults to present
    ClientConfigProfile profile =
        ClientConfigProfile.load(
            LoadClientConfigProfileOptions.newBuilder()
                .setConfigFileData(("[profile.default]\n" + "api_key = \"my-api-key\"").getBytes())
                .setEnvOverrides(Collections.emptyMap())
                .build());
    WorkflowServiceStubsOptions stubsOptions = profile.toWorkflowServiceStubsOptions();
    Assert.assertEquals(1, stubsOptions.getGrpcMetadataProviders().size());
    Assert.assertTrue(stubsOptions.getEnableHttps());

    // But when API key is not present, neither should TLS be
    profile =
        ClientConfigProfile.load(
            LoadClientConfigProfileOptions.newBuilder()
                .setConfigFileData(("[profile.default]\n" + "address = \"whatever\"").getBytes())
                .setEnvOverrides(Collections.emptyMap())
                .build());
    stubsOptions = profile.toWorkflowServiceStubsOptions();
    Assert.assertNull(stubsOptions.getGrpcMetadataProviders());
    Assert.assertFalse(stubsOptions.getEnableHttps());
  }
}
