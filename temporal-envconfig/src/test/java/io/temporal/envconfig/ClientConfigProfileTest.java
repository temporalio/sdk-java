package io.temporal.envconfig;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.io.*;
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

  @Test
  public void loadClientOptionsTLS() throws IOException {
    String clientCertData =
        "-----BEGIN CERTIFICATE-----\n"
            + "MIIFazCCA1OgAwIBAgIUVKpGlS3qKbD6H5bBKtBo7o5TdtAwDQYJKoZIhvcNAQEL\n"
            + "BQAwRTELMAkGA1UEBhMCQVUxEzARBgNVBAgMClNvbWUtU3RhdGUxITAfBgNVBAoM\n"
            + "GEludGVybmV0IFdpZGdpdHMgUHR5IEx0ZDAeFw0yNTA5MTYxNjQ1MTVaFw0yNjA5\n"
            + "MTYxNjQ1MTVaMEUxCzAJBgNVBAYTAkFVMRMwEQYDVQQIDApTb21lLVN0YXRlMSEw\n"
            + "HwYDVQQKDBhJbnRlcm5ldCBXaWRnaXRzIFB0eSBMdGQwggIiMA0GCSqGSIb3DQEB\n"
            + "AQUAA4ICDwAwggIKAoICAQC1oCNiM0gvBRdxelR9G27uXY+HIvjeSbs5MLs6jJke\n"
            + "ZSiedMYnQCQQYOOsZ+UGxdm+PTway2RMNMottIg4D3MNxG0S66fpTJgpgejHXAge\n"
            + "SLe/am4OS8l1fRr2Tfjr5aqVHJnLVzI152bIwEiPwVDhoPTwJNmbRYFvj487pM5g\n"
            + "StIJ3z17buMGxDfi7pgrCU7+3Bifd1xyfq8+S/A5BEGQxbNpYBWIWbeCBZkRPsXM\n"
            + "OAmiHILfRmXFsMX9lmA6ky4i72CsSoN3y7a/k5Rs2wsFJxxeEV21P0cKSz9KXO+n\n"
            + "2kbYazQgriKHbTEColNtfaQzZQPVILM0l4lmKtKwnl+6g6rhIlIGiLI7WbluAnxR\n"
            + "Yu+irZPA71K2Q5+SZ553TmDP5kkmiKUHQwiphnquekgDbGS75WBtOqDAihTPsRfN\n"
            + "0DbFJpN8x9RBdQ2gu5J5Svia1Up4BeBPqDZzvPS0FwMCmeDZqEL9uS9UJi4NbF8I\n"
            + "mTA0oB5R4C6jciwDfGJ7p/6gs/bmUtT/5I4bbgTTW1RSeU6axafWYwO6g8be4yyS\n"
            + "KbwlOIbyyUL23B8ihsZwwOcB06kfAo5Pk7Xz96YMFyXZ8e5cGG+CJZyPWNruLdkG\n"
            + "sXx+eWn6ILtpfFSH6KOOqM+myvZJM/5vr07vfYHfXrT2B786ZfMd5YdLQlkMTmqn\n"
            + "SQIDAQABo1MwUTAdBgNVHQ4EFgQU7aVU3qX5IByXlYOdbWeMqr/jKI4wHwYDVR0j\n"
            + "BBgwFoAU7aVU3qX5IByXlYOdbWeMqr/jKI4wDwYDVR0TAQH/BAUwAwEB/zANBgkq\n"
            + "hkiG9w0BAQsFAAOCAgEAjH3XGNjIEWSU9hB3KKSk0uWVAeVBiUGl/UZW+7NMjxj7\n"
            + "NnpNoMGHmq4YGA2rnl70YrGMYPX5pclMHMSEkWxh4mAftv8mD9T5h0zdZuepfDS6\n"
            + "pEABWP9SLbQYTNCNG3WGWA35Vv916pf97Fg4xKSMBVEzv2JdWTUU2/72KRm2sIIe\n"
            + "Lt787XU6QJ+QypsG9FqRATX+oaLPhFx5DQQNdrBy08Idi3GM9y/xCDnA8do3fQcq\n"
            + "PB/92xTJK2I4oEjpWnNbxJnlfUlajlMQn56aXmTYQ7fViWl+fKsTJcg/6KM525Gg\n"
            + "eiKeVYlKHoSV8tE2bpRTl1YePPJSCjm0Hi74O4gglrU/xBR173amGHiBAq5JjRwY\n"
            + "AbM1et5+juSy+R/6E+zsa2lBATenutuy6IukfUpeq05Oh/mYCBIuZifAYbLmyl3e\n"
            + "/a2iLE0Un1ePNCGBoW8HOWwf1agnK1mdNpC0X0Nef9dnb/lZs8jfYrCwsqfvfbyV\n"
            + "vAr8qgArGT6XmLzorW2my933nD0O7ZQgQ4pMLQQBeVYp5W+lfsHCBEFbM6XHujHB\n"
            + "KiOhgD559tIfaQlZ5O9K992F2MGKJlBoYnTUzRZ+aDoMEFs4U2uec+Y1guZF5WoV\n"
            + "YaFiWkczjL/fjoN+kzw3tWO6N1SnlWgG3WFVBg5jgYc4onuDdtQyhdWkZH2fNDA=\n"
            + "-----END CERTIFICATE-----";

    String clientKeyData =
        "-----BEGIN PRIVATE KEY-----\n"
            + "MIIJQwIBADANBgkqhkiG9w0BAQEFAASCCS0wggkpAgEAAoICAQC1oCNiM0gvBRdx\n"
            + "elR9G27uXY+HIvjeSbs5MLs6jJkeZSiedMYnQCQQYOOsZ+UGxdm+PTway2RMNMot\n"
            + "tIg4D3MNxG0S66fpTJgpgejHXAgeSLe/am4OS8l1fRr2Tfjr5aqVHJnLVzI152bI\n"
            + "wEiPwVDhoPTwJNmbRYFvj487pM5gStIJ3z17buMGxDfi7pgrCU7+3Bifd1xyfq8+\n"
            + "S/A5BEGQxbNpYBWIWbeCBZkRPsXMOAmiHILfRmXFsMX9lmA6ky4i72CsSoN3y7a/\n"
            + "k5Rs2wsFJxxeEV21P0cKSz9KXO+n2kbYazQgriKHbTEColNtfaQzZQPVILM0l4lm\n"
            + "KtKwnl+6g6rhIlIGiLI7WbluAnxRYu+irZPA71K2Q5+SZ553TmDP5kkmiKUHQwip\n"
            + "hnquekgDbGS75WBtOqDAihTPsRfN0DbFJpN8x9RBdQ2gu5J5Svia1Up4BeBPqDZz\n"
            + "vPS0FwMCmeDZqEL9uS9UJi4NbF8ImTA0oB5R4C6jciwDfGJ7p/6gs/bmUtT/5I4b\n"
            + "bgTTW1RSeU6axafWYwO6g8be4yySKbwlOIbyyUL23B8ihsZwwOcB06kfAo5Pk7Xz\n"
            + "96YMFyXZ8e5cGG+CJZyPWNruLdkGsXx+eWn6ILtpfFSH6KOOqM+myvZJM/5vr07v\n"
            + "fYHfXrT2B786ZfMd5YdLQlkMTmqnSQIDAQABAoICAEdvJHuLD+juZ7oKExDhqU+3\n"
            + "HKxZ5OgIt8pWkE0G33JE9yTbaNQnNgf7E5DLjBiN2IYqL2madWhDmwE+8BScfvP7\n"
            + "PasjZHct2Or6XUOLvuWqVBrFEWQuCp5bBi+5mx2sxtq+1P5U3Tq2OIbcma1wqw8S\n"
            + "70NEOxIG1FG8dOlQeJsG0nVviA70HfabVh+7F75VeuxiRIzztTiS+vnVhDXopqD6\n"
            + "IQZg9BccskBBU2Kk/cbEg4VvEUoftgd672Q9sLtZB9xVqgAZjnufc9EFimsF+9+a\n"
            + "8l1NFz4iFR7HWa01wEyUXSjhgS8ZThtVpuESVx3JPLu6DzfUFKeqsi+syBEPOTbG\n"
            + "V7aaarzQoC5FX8pVW7YOk8wgPCTUb1HUH7kBSO9SXO5GC19slGHc7n5W42SiHPwc\n"
            + "39gmoST+TpILpbYd8arCTwhLzmGMwgNEMx0MVhGyfA7o6NGLcAHub9z8ri9enxTn\n"
            + "FeLBk3MEQM4oki0lgJGJgATgsFEzCorRG2X5cPoolD7Wfjin4rO1iQrgGETaCRyp\n"
            + "rq73iIo6EdjEUKys17oueuozcZnDtDiVPcZeLhVw2XiWRgLcA77fC2TbwZpj1d9B\n"
            + "YSwbSLH+tWi9MWHz80sc1/sskO0yoPqTqXZKsUW+utastXrRoSehRetgQB0Nado7\n"
            + "2dYbXJMeg5fC0y79lvXBAoIBAQD/11hjur5WSk8Hv4fANqb5nei1AgcWu8dmnqGW\n"
            + "j8ucWgNtF/YzE8DSWi9c5mfRz+YJneYB8jpIEVlni5X0s/Dnbyw33ksua1HcBTkT\n"
            + "IYvCcewLJWy9IViAsdSV9wO3LU+PLhpqo6Nl79rOmvqhXL1nsWyCW8JUKPQmJSuK\n"
            + "yWbMIGI9/6YOyAhKAbqD3lja+Yh1XSsCfjVd7tT3glPy8fCaoqZlDYEP/uCT/nDL\n"
            + "xGSZ+wkOQ3nS7EiuBN2JFsQDEg8vLr8JVcpcgtVvzFW6+ZFL7sgr4U/rra/CI7Ym\n"
            + "D+xjGwJ2hpuyM5X+15jPkXrX01TGIuVUnf11HH74Sh5Jpci/AoIBAQC1vP/nardR\n"
            + "vZXRwc7ZLJqTzv+ITGRZGsObM1fHWD0keijE5S08bBs0SwxNyvwTZduovHTj+Ulb\n"
            + "4WVJEtJo4ucKNkAnh8W/1IQdVuSQkvQfWgQS2PuCkqgIrBOBxm2qOAczltcpYSyk\n"
            + "aWbQ0U7xVA+pdHgFE+cSV2Jtz/M6Tv6tAZtQptovwbBU0fnaEcV7m46OZdRyrkyd\n"
            + "MfLVv4b9iJW8+SamEYvkCDc9k6DEf4xmp4w9YtpmY/Hky5YfXA1jNe0r8OZZvULB\n"
            + "ju0NgVXQQiZCZ36j2/6NQ/DSdqPLZn/ywAeScsf5VLCqxRPghDQVq68u+mLXTf2v\n"
            + "6dyX0HYJKMn3AoIBACcqn4R9BUCOlbS49J5Pf5Z9Py/exJkNXERwiopTLzebbCTa\n"
            + "Yz2Ei6NoXRHa0BAFxNC6FIk9vQBlb4tzihxxI7M6iMlwxY+wrFKDli5Al3XIHPvD\n"
            + "2fbGURc6ojHnI/F6BVEFHNQwgwZLBvNUNIRZf0GNnvAB/ikGMAJa9GSF2q/rUT7u\n"
            + "kUx4ARTbWONxOackRmi5P6ldCux7cK0HjbSGp2/08IZN3/FD8ruVW01GnqQYE1XU\n"
            + "rKTGuYWyhvvCuXVC4YI2pNZYBOfOu8AmxwUdycmXH5vgHW0WJO8SqoL/MxAlBWaB\n"
            + "yvon/ZGLDgDQ476AwtymYPdoTHIOT73REvvxXl8CggEBAI6a1G7hVSGl0wa5vja5\n"
            + "gj3TYr2vu9oTX0PMQOeiPK//zzfY4OsVpS8eaHQugCg0d+1qm4o7lS2sqo5xX3t/\n"
            + "+G0R7rtWFXyWJGjlQwqS1U44kxO7AXgO3h2X8OKXMnwr5LK9fO3yW1ZTgqL+aqSB\n"
            + "Ip0EUB0j5eCFgy3JzACH9d0JcrcRhgmNQXD9JsHPyhdZE7529wJZ9LIwfGzvEdyl\n"
            + "rWGQW5xaDlwLelUuHyuxLhlrBWcxx1AqwqeWfKD02Whs60Lcj9QA5338SdScFRsK\n"
            + "nPzkOwIW4SI2GqT7BUHYlzODLS3kNThXFR2a8SLuefQ7OIZzYNWzVAoSRs81ezlq\n"
            + "sTcCggEBAMQ3CvnAI1m+uCwV3N8iUB/PFt2vgjfOdhOCJUag/50AY7lKscKGQxM0\n"
            + "S066xlqqQcUz2i2j1TF5HnxSQpk3ef3HGj/0jn9bYtt7+MsotlDZJ6DHTexy1f7J\n"
            + "nX+uRC7H2riWkhwDXiqxDR9L6/lC8XeTOxLD1Up7IYA0elpJw86pkkdfHJ/kefey\n"
            + "KY6PKFLd/hMqt+XJ6Q/HQR3VN2wXpKtQ2O4K59I1XW9cWByVA3VjQ25Z5O6DIlOh\n"
            + "otbfGAWJ0gWikIbnLRRVCK0N5yDqFlnMe403AZfK0ItwwIyIRsjEeaLUL3NoQYQP\n"
            + "YymQE1T0jkQc7f+tsDiNuDn91NTlCeQ=\n"
            + "-----END PRIVATE KEY-----\n";

    // Since API key is present, TLS defaults to present
    ClientConfigProfile profile =
        ClientConfigProfile.load(
            LoadClientConfigProfileOptions.newBuilder()
                .setConfigFileData(
                    ("[profile.default]\n"
                            + "[profile.default.tls]\n"
                            + "client_cert_data = \"\"\""
                            + clientCertData
                            + "\"\"\"\n"
                            + "client_key_data = \"\"\""
                            + clientKeyData
                            + "\"\"\"")
                        .getBytes())
                .setEnvOverrides(Collections.emptyMap())
                .build());
    WorkflowServiceStubsOptions stubsOptions = profile.toWorkflowServiceStubsOptions();
    Assert.assertNotNull(stubsOptions.getSslContext());
    SslContext context = stubsOptions.getSslContext();
    Assert.assertTrue(context.isClient());
  }

  @Test
  public void parseToml() throws IOException {
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
    ClientConfig clientConfig = ClientConfig.fromToml(toml.getBytes());
    Assert.assertEquals(1, clientConfig.getProfiles().size());
    ClientConfigProfile profile = clientConfig.getProfiles().get("foo");
    Assert.assertNotNull(profile);
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
    String generatedTomlString = new String(ClientConfig.toTomlAsBytes(clientConfig));
    ClientConfig clientConfig2 = ClientConfig.fromToml(generatedTomlString.getBytes());
    Assert.assertEquals(clientConfig, clientConfig2);
  }
}
