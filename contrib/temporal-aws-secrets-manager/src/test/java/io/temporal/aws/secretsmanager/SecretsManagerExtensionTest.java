package io.temporal.aws.secretsmanager;

import static io.temporal.aws.secretsmanager.TestHelpers.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uber.m3.tally.Scope;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.temporal.authorization.AuthorizationGrpcMetadataProvider;
import io.temporal.envconfig.ClientConfigProfile;
import io.temporal.envconfig.LoadClientConfigProfileOptions;
import io.temporal.serviceclient.GrpcMetadataProvider;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.serviceclient.WorkflowServiceStubsPlugin;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.junit.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

public class SecretsManagerExtensionTest {
  private static final String PROFILE = "[profile.default]\naddress = \"localhost:7233\"\n";
  private static final String EXT = "[profile.default.ext.aws_secrets_manager]\n";
  private static final String EXT_TLS = "[profile.default.ext.aws_secrets_manager.tls]\n";
  private static final String API_KEY_PROFILE = PROFILE + EXT + "api_key_id = \"id\"\n";
  private static final String ALL_PROFILE =
      PROFILE
          + EXT
          + "api_key_id = \"api-key\"\n"
          + EXT_TLS
          + "client_cert_id = \"bundle\"\n"
          + "client_key_id = \"bundle\"\n"
          + "server_ca_cert_id = \"ca\"\n";

  /** A fake Secrets Manager client that returns {@code secrets}, counting fetches of each. */
  private static final class FakeSecrets {
    final Map<String, Object> secrets = new ConcurrentHashMap<>();
    final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final AtomicInteger version = new AtomicInteger();
    final SecretsManagerClient client = mock(SecretsManagerClient.class);

    FakeSecrets() {
      when(client.getSecretValue(any(GetSecretValueRequest.class)))
          .thenAnswer(
              inv -> {
                String id = inv.<GetSecretValueRequest>getArgument(0).secretId();
                calls.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
                Object value = secrets.get(id);
                if (value == null) {
                  throw SecretsManagerException.builder()
                      .message("no secret " + id)
                      .awsErrorDetails(
                          AwsErrorDetails.builder().errorCode("ResourceNotFoundException").build())
                      .build();
                }
                if (value instanceof RuntimeException) {
                  throw (RuntimeException) value;
                }
                return GetSecretValueResponse.builder()
                    .secretString((String) value)
                    .versionId("v" + version.incrementAndGet())
                    .build();
              });
    }

    FakeSecrets put(String id, Object value) {
      secrets.put(id, value);
      return this;
    }

    int calls(String id) {
      AtomicInteger n = calls.get(id);
      return n != null ? n.get() : 0;
    }
  }

  private static FakeSecrets allSecrets() {
    return new FakeSecrets()
        .put("api-key", "secret-key")
        .put("bundle", TEST_CERT_A + TEST_KEY_A)
        .put("ca", TEST_CERT_A);
  }

  private static ClientConfigProfile load(
      String toml, Map<String, String> env, SecretsManagerExtension.Builder extension)
      throws Exception {
    return ClientConfigProfile.load(
        LoadClientConfigProfileOptions.newBuilder()
            .setConfigFileData(toml.getBytes(StandardCharsets.UTF_8))
            .setEnvOverrides(env)
            .setExtensions(Collections.singletonList(extension.build()))
            .build());
  }

  private static SecretsManagerExtension.Builder extension(FakeSecrets secrets) {
    return SecretsManagerExtension.newBuilder()
        .setSecretsClient(secrets.client)
        .setProcessEnv(Collections.emptyMap());
  }

  private static SecretsManagerPlugin plugin(ClientConfigProfile profile) {
    assertEquals(1, profile.getPlugins().size());
    return (SecretsManagerPlugin) profile.getPlugins().get(0);
  }

  private static SecretsManagerPlugin load(String toml, FakeSecrets secrets) throws Exception {
    return plugin(load(toml, Collections.emptyMap(), extension(secrets)));
  }

  private static String ids(SecretsManagerPlugin plugin) {
    SecretCache.SecretIds ids = plugin.ids;
    return String.join(",", ids.apiKey, ids.clientCert, ids.clientKey, ids.serverCaCert);
  }

  /** Applies the plugin to the profile's service stubs options, without creating stubs. */
  private static WorkflowServiceStubsOptions configure(ClientConfigProfile profile) {
    WorkflowServiceStubsOptions.Builder builder =
        WorkflowServiceStubsOptions.newBuilder(profile.toWorkflowServiceStubsOptions());
    plugin(profile).configureServiceStubs(builder);
    WorkflowServiceStubsOptions options = builder.build();
    plugin(profile).connectServiceClient(options, () -> null);
    return options;
  }

  private static String authorization(WorkflowServiceStubsOptions options) {
    List<GrpcMetadataProvider> providers =
        options.getGrpcMetadataProviders().stream()
            .filter(p -> p instanceof AuthorizationGrpcMetadataProvider)
            .collect(Collectors.toList());
    assertEquals(1, providers.size());
    return providers
        .get(0)
        .getMetadata()
        .get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER));
  }

  @Test
  public void noSecretIdsMeansNoPlugin() throws Exception {
    assertTrue(
        load(PROFILE, Collections.emptyMap(), extension(new FakeSecrets())).getPlugins().isEmpty());
    assertTrue(
        load(PROFILE + EXT, Collections.emptyMap(), extension(new FakeSecrets()))
            .getPlugins()
            .isEmpty());
  }

  @Test
  public void secretIdsAreLoadedFromTomlAndEnv() throws Exception {
    String toml =
        PROFILE
            + EXT
            + "api_key_id = \"toml-api-key\"\n"
            + EXT_TLS
            + "client_cert_id = \"toml-cert\"\n"
            + "client_key_id = \"toml-key\"\n"
            + "server_ca_cert_id = \"toml-ca\"\n";
    assertEquals("toml-api-key,toml-cert,toml-key,toml-ca", ids(load(toml, new FakeSecrets())));

    // Env vars override the file, and set-but-empty env vars unset it
    Map<String, String> env = new HashMap<>();
    env.put("TEMPORAL_EXT_AWS_SECRETS_MANAGER_API_KEY_ID", "env-api-key");
    env.put("TEMPORAL_EXT_AWS_SECRETS_MANAGER_TLS_SERVER_CA_CERT_ID", "");
    assertEquals(
        "env-api-key,toml-cert,toml-key,",
        ids(plugin(load(toml, env, extension(new FakeSecrets())))));

    // Env vars are ignored when env loading is disabled
    ClientConfigProfile profile =
        ClientConfigProfile.load(
            LoadClientConfigProfileOptions.newBuilder()
                .setConfigFileData(PROFILE.getBytes(StandardCharsets.UTF_8))
                .setEnvOverrides(
                    Collections.singletonMap("TEMPORAL_EXT_AWS_SECRETS_MANAGER_API_KEY_ID", "id"))
                .setDisableEnv(true)
                .setExtensions(Collections.singletonList(extension(new FakeSecrets()).build()))
                .build());
    assertTrue(profile.getPlugins().isEmpty());
  }

  @Test
  public void invalidConfig() {
    String[][] cases = {
      {EXT + "foo = \"x\"", "unknown key \"foo\" in ext.aws_secrets_manager"},
      {EXT_TLS + "foo = \"x\"", "unknown key \"foo\" in ext.aws_secrets_manager.tls"},
      {EXT + "tls = \"x\"", "ext.aws_secrets_manager.tls must be a table, got string"},
      {EXT + "api_key_id = 1", "ext.aws_secrets_manager.api_key_id must be a string, got number"},
      {
        EXT_TLS + "client_cert_id = []",
        "ext.aws_secrets_manager.tls.client_cert_id must be a string, got array"
      },
      {
        "api_key = \"key\"\n" + EXT + "api_key_id = \"id\"",
        "invalid config: API key secret ID cannot be set with an API key"
      },
      {
        "[profile.default.tls]\nclient_cert_data = \"x\"\nclient_key_data = \"y\"\n"
            + EXT_TLS
            + "client_cert_id = \"c\"\nclient_key_id = \"k\"",
        "invalid config: TLS client cert secret ID cannot be set with client cert path or data; "
            + "TLS client key secret ID cannot be set with client key path or data"
      },
      {
        "[profile.default.tls]\nserver_ca_cert_data = \"x\"\n"
            + EXT_TLS
            + "server_ca_cert_id = \"ca\"",
        "invalid config: TLS server CA cert secret ID cannot be set with server CA cert path or"
            + " data"
      },
      {
        "[profile.default.tls]\ndisabled = true\n" + EXT_TLS + "server_ca_cert_id = \"ca\"",
        "invalid config: TLS secret IDs cannot be set when TLS is disabled"
      },
      {
        EXT_TLS + "client_cert_id = \"c\"",
        "invalid config: TLS client cert and key secret IDs must be set together"
      },
    };
    for (String[] c : cases) {
      RuntimeException e =
          assertThrows(
              c[0], RuntimeException.class, () -> load(PROFILE + c[0] + "\n", new FakeSecrets()));
      assertEquals(
          c[0], "Client config extension \"aws_secrets_manager\" failed: " + c[1], e.getMessage());
    }

    // Across sources
    RuntimeException e =
        assertThrows(
            RuntimeException.class,
            () ->
                load(
                    PROFILE + "api_key = \"key\"\n",
                    Collections.singletonMap("TEMPORAL_EXT_AWS_SECRETS_MANAGER_API_KEY_ID", "id"),
                    extension(new FakeSecrets())));
    assertTrue(
        e.getMessage(), e.getMessage().endsWith("API key secret ID cannot be set with an API key"));
  }

  @Test
  public void apiKeySecretIsAllowedWithTlsDisabled() throws Exception {
    load(
        PROFILE + "[profile.default.tls]\ndisabled = true\n" + EXT + "api_key_id = \"id\"\n",
        new FakeSecrets());
  }

  @Test
  public void refreshIntervalAndMaxStaleness() throws Exception {
    FakeSecrets secrets = new FakeSecrets();
    SecretCache cache = load(API_KEY_PROFILE, secrets).cache;
    assertEquals(TimeUnit.MINUTES.toNanos(5), cache.refreshIntervalNanos);
    assertEquals(TimeUnit.HOURS.toNanos(24), cache.maxStalenessNanos);

    cache =
        plugin(
                load(
                    API_KEY_PROFILE,
                    Collections.emptyMap(),
                    extension(secrets)
                        .setRefreshInterval(Duration.ofMinutes(1))
                        .setMaxStaleness(Duration.ZERO)))
            .cache;
    assertEquals(TimeUnit.MINUTES.toNanos(1), cache.refreshIntervalNanos);
    assertEquals(TimeUnit.HOURS.toNanos(24), cache.maxStalenessNanos);

    // Max staleness is at least the refresh interval
    cache =
        plugin(
                load(
                    API_KEY_PROFILE,
                    Collections.emptyMap(),
                    extension(secrets)
                        .setRefreshInterval(Duration.ofHours(2))
                        .setMaxStaleness(Duration.ofHours(1))))
            .cache;
    assertEquals(TimeUnit.HOURS.toNanos(2), cache.maxStalenessNanos);

    RuntimeException e =
        assertThrows(
            RuntimeException.class,
            () ->
                load(
                    API_KEY_PROFILE,
                    Collections.emptyMap(),
                    extension(secrets).setRefreshInterval(Duration.ofSeconds(-1))));
    assertTrue(e.getMessage(), e.getMessage().endsWith("refreshInterval must not be negative"));

    // From SECRETS_MANAGER_TTL in the process env
    assertEquals(0, ttlCache(secrets, "0").refreshIntervalNanos);
    assertEquals(TimeUnit.SECONDS.toNanos(600), ttlCache(secrets, "600").refreshIntervalNanos);
    // The option takes precedence
    assertEquals(
        TimeUnit.SECONDS.toNanos(1),
        plugin(
                load(
                    API_KEY_PROFILE,
                    Collections.emptyMap(),
                    extension(secrets)
                        .setProcessEnv(Collections.singletonMap("SECRETS_MANAGER_TTL", "600"))
                        .setRefreshInterval(Duration.ofSeconds(1))))
            .cache
            .refreshIntervalNanos);
    for (String invalid : new String[] {"-1", "1.5", "abc", "99999999999999999999"}) {
      e = assertThrows(RuntimeException.class, () -> ttlCache(secrets, invalid));
      assertEquals(
          "Client config extension \"aws_secrets_manager\" failed: invalid SECRETS_MANAGER_TTL \""
              + invalid
              + "\", must be a non-negative whole number of seconds",
          e.getMessage());
    }
  }

  private static SecretCache ttlCache(FakeSecrets secrets, String ttl) throws Exception {
    return plugin(
            load(
                API_KEY_PROFILE,
                Collections.emptyMap(),
                extension(secrets)
                    .setProcessEnv(Collections.singletonMap("SECRETS_MANAGER_TTL", ttl))))
        .cache;
  }

  @Test
  public void configureAppliesSecrets() throws Exception {
    FakeSecrets secrets = allSecrets();
    ClientConfigProfile profile = load(ALL_PROFILE, Collections.emptyMap(), extension(secrets));
    WorkflowServiceStubsOptions options = configure(profile);
    assertEquals("Bearer secret-key", authorization(options));
    assertNotNull(options.getSslContext());
    assertTrue(options.getSslContext().isClient());
    assertEquals(1, secrets.calls("api-key"));
    assertEquals(1, secrets.calls("bundle"));
    assertEquals(1, secrets.calls("ca"));
    // An API key enables TLS by default, like an API key from envconfig
    ClientConfigProfile apiKeyProfile =
        load(API_KEY_PROFILE, Collections.emptyMap(), extension(new FakeSecrets().put("id", "k")));
    assertTrue(configure(apiKeyProfile).getEnableHttps());
  }

  @Test
  public void configureCombinesSecretsWithProfileTls() throws Exception {
    // CA from the profile, client cert and key from a secret
    String toml =
        PROFILE
            + "[profile.default.tls]\nserver_name = \"sni\"\nserver_ca_cert_data = \"\"\"\n"
            + TEST_CERT_B
            + "\"\"\"\n"
            + EXT_TLS
            + "client_cert_id = \"bundle\"\nclient_key_id = \"bundle\"\n";
    FakeSecrets secrets = allSecrets();
    WorkflowServiceStubsOptions options =
        configure(load(toml, Collections.emptyMap(), extension(secrets)));
    assertNotNull(options.getSslContext());
    assertEquals(1, secrets.calls("bundle"));

    // CA from a secret, client cert and key from the profile
    toml =
        PROFILE
            + "[profile.default.tls]\nclient_cert_data = \"\"\"\n"
            + TEST_CERT_A
            + "\"\"\"\nclient_key_data = \"\"\"\n"
            + TEST_KEY_A
            + "\"\"\"\n"
            + EXT_TLS
            + "server_ca_cert_id = \"ca\"\n";
    options = configure(load(toml, Collections.emptyMap(), extension(secrets)));
    assertNotNull(options.getSslContext());
    assertEquals(1, secrets.calls("ca"));
  }

  @Test
  public void configureConflicts() throws Exception {
    ClientConfigProfile profile =
        load(ALL_PROFILE, Collections.emptyMap(), extension(allSecrets()));
    SecretsManagerPlugin plugin = plugin(profile);

    WorkflowServiceStubsOptions.Builder withApiKey =
        WorkflowServiceStubsOptions.newBuilder(profile.toWorkflowServiceStubsOptions())
            .addApiKey(() -> "key");
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> plugin.configureServiceStubs(withApiKey));
    assertEquals(
        "cannot use API key from AWS Secrets Manager, API key already set", e.getMessage());

    // An SSL context that envconfig didn't create
    WorkflowServiceStubsOptions.Builder withSsl =
        WorkflowServiceStubsOptions.newBuilder(profile.toWorkflowServiceStubsOptions())
            .setSslContext(GrpcSslContexts.forClient().build());
    e = assertThrows(IllegalStateException.class, () -> plugin.configureServiceStubs(withSsl));
    assertEquals(
        "cannot use TLS config from AWS Secrets Manager, SSL context already set", e.getMessage());
  }

  @Test
  public void configureFailsIfAFetchFails() throws Exception {
    ClientConfigProfile profile =
        load(API_KEY_PROFILE, Collections.emptyMap(), extension(new FakeSecrets()));
    SecretCache.SecretFetchException e =
        assertThrows(SecretCache.SecretFetchException.class, () -> configure(profile));
    assertEquals(
        "failed fetching AWS Secrets Manager secret \"id\": no secret id",
        e.getMessage().replaceAll(" \\(Service:.*", ""));
  }

  @Test
  public void apiKeyIsRefreshedWhenUsed() throws Exception {
    FakeSecrets secrets = new FakeSecrets().put("id", "key1");
    ClientConfigProfile profile =
        load(
            API_KEY_PROFILE,
            Collections.emptyMap(),
            extension(secrets).setRefreshInterval(Duration.ZERO));
    plugin(profile).minRefreshIntervalNanos = TimeUnit.MILLISECONDS.toNanos(20);
    WorkflowServiceStubsOptions options = configure(profile);
    assertEquals("Bearer key1", authorization(options));

    secrets.put("id", "key2");
    waitUntil(() -> "Bearer key2".equals(authorization(options)));
  }

  @Test
  public void serviceStubsRunThePlugin() throws Exception {
    FakeSecrets secrets = allSecrets();
    ClientConfigProfile profile = load(ALL_PROFILE, Collections.emptyMap(), extension(secrets));
    WorkflowServiceStubs stubs =
        WorkflowServiceStubs.newServiceStubs(profile.toWorkflowServiceStubsOptions());
    try {
      assertEquals(1, secrets.calls("api-key"));
      assertEquals(1, secrets.calls("bundle"));
      assertEquals(1, secrets.calls("ca"));
    } finally {
      stubs.shutdownNow();
    }
  }

  @Test
  public void apiKeyMetricsUseFinalMetricsScope() throws Exception {
    ClientConfigProfile profile =
        load(API_KEY_PROFILE, Collections.emptyMap(), extension(new FakeSecrets().put("id", "k")));
    // A metrics plugin registered after this one, so its scope isn't set yet when this plugin
    // configures the options
    Scope scope = mock(Scope.class, RETURNS_DEEP_STUBS);
    WorkflowServiceStubsPlugin metricsPlugin =
        new WorkflowServiceStubsPlugin() {
          @Nonnull
          @Override
          public String getName() {
            return "test.metrics";
          }

          @Override
          public void configureServiceStubs(@Nonnull WorkflowServiceStubsOptions.Builder builder) {
            builder.setMetricsScope(scope);
          }

          @Nonnull
          @Override
          public WorkflowServiceStubs connectServiceClient(
              @Nonnull WorkflowServiceStubsOptions options,
              @Nonnull Supplier<WorkflowServiceStubs> next) {
            return next.get();
          }
        };
    WorkflowServiceStubs stubs =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder(profile.toWorkflowServiceStubsOptions())
                .setPlugins(plugin(profile), metricsPlugin)
                .build());
    try {
      verify(scope, atLeastOnce())
          .tagged(
              argThat(
                  tags ->
                      Telemetry.SECRET_TYPE_API_KEY.equals(tags.get(Telemetry.LABEL_SECRET_TYPE))));
    } finally {
      stubs.shutdownNow();
    }
  }
}
