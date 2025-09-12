package io.temporal.envconfig;

import io.grpc.Metadata;
import java.io.IOException;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Test;

public class ClientConfigProfileTest {

  @Test
  public void loadProfile() throws IOException {
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
  }
}
