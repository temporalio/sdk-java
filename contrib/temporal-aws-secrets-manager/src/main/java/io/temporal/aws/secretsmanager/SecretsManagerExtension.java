package io.temporal.aws.secretsmanager;

import io.temporal.common.Experimental;
import io.temporal.envconfig.ClientConfigExtension;
import io.temporal.envconfig.ClientConfigExtensionRequest;
import io.temporal.envconfig.ClientConfigProfile;
import io.temporal.envconfig.ClientConfigTLS;
import io.temporal.serviceclient.WorkflowServiceStubsPlugin;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Client config extension that loads client credentials (API key and/or mTLS material) from AWS
 * Secrets Manager.
 *
 * <p>Pass to {@link
 * io.temporal.envconfig.LoadClientConfigProfileOptions.Builder#setExtensions(List)}:
 *
 * <pre>{@code
 * ClientConfigProfile profile =
 *     ClientConfigProfile.load(
 *         LoadClientConfigProfileOptions.newBuilder()
 *             .setExtensions(Collections.singletonList(SecretsManagerExtension.getDefaultInstance()))
 *             .build());
 * WorkflowServiceStubs service =
 *     WorkflowServiceStubs.newServiceStubs(profile.toWorkflowServiceStubsOptions());
 * }</pre>
 *
 * <p>Secret IDs are configured in the {@code profile.<profile>.ext.aws_secrets_manager} TOML table
 * or with {@code TEMPORAL_EXT_AWS_SECRETS_MANAGER_*} environment variables. See the module README
 * for details.
 */
@Experimental
public final class SecretsManagerExtension implements ClientConfigExtension {
  /** The name of the extension, which is also its table key under {@code profile.<profile>.ext}. */
  public static final String EXTENSION_NAME = "aws_secrets_manager";

  private static final String ENV_API_KEY_ID = "TEMPORAL_EXT_AWS_SECRETS_MANAGER_API_KEY_ID";
  private static final String ENV_CLIENT_CERT_ID =
      "TEMPORAL_EXT_AWS_SECRETS_MANAGER_TLS_CLIENT_CERT_ID";
  private static final String ENV_CLIENT_KEY_ID =
      "TEMPORAL_EXT_AWS_SECRETS_MANAGER_TLS_CLIENT_KEY_ID";
  private static final String ENV_SERVER_CA_CERT_ID =
      "TEMPORAL_EXT_AWS_SECRETS_MANAGER_TLS_SERVER_CA_CERT_ID";

  // Same env var and default as the AWS Parameters and Secrets Lambda Extension's cache TTL
  private static final String ENV_SECRETS_MANAGER_TTL = "SECRETS_MANAGER_TTL";
  private static final Pattern WHOLE_NUMBER = Pattern.compile("[0-9]+");
  private static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofMinutes(5);
  private static final Duration DEFAULT_MAX_STALENESS = Duration.ofHours(24);

  private static final SecretsManagerExtension DEFAULT_INSTANCE = newBuilder().build();

  /** Returns an extension with the default options. */
  public static SecretsManagerExtension getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  /** Creates a builder for an extension with non-default options. */
  public static Builder newBuilder() {
    return new Builder();
  }

  // A SecretsManagerClient, held as an Object so that this class can be loaded without the AWS SDK
  @Nullable private final Object secretsClient;
  @Nullable private final Duration refreshInterval;
  @Nullable private final Duration maxStaleness;
  private final Map<String, String> processEnv;

  private SecretsManagerExtension(Builder builder) {
    this.secretsClient = builder.secretsClient;
    this.refreshInterval = builder.refreshInterval;
    this.maxStaleness = builder.maxStaleness;
    this.processEnv = builder.processEnv;
  }

  /** The extension name, {@code aws_secrets_manager}. */
  @Nonnull
  @Override
  public String getName() {
    return EXTENSION_NAME;
  }

  /**
   * Reads secret IDs from the profile and env, and returns a plugin that fetches the configured
   * secrets, or null if none are configured.
   */
  @Nullable
  @Override
  public WorkflowServiceStubsPlugin getClientPlugin(@Nonnull ClientConfigExtensionRequest request) {
    ClientConfigProfile profile = request.getProfile();
    SecretCache.SecretIds ids = loadSecretIds(profile, request.getEnv());
    if (ids.isEmpty()) {
      return null;
    }

    Duration refresh = refreshInterval();
    Duration staleness = maxStaleness;
    if (staleness == null || staleness.isZero() || staleness.isNegative()) {
      staleness = DEFAULT_MAX_STALENESS;
    }
    if (staleness.compareTo(refresh) < 0) {
      staleness = refresh;
    }

    Fetcher fetcher =
        secretsClient != null
            ? SdkFetcher.create(secretsClient)
            : Fetcher.newDefaultFetcher(processEnv);
    SecretCache cache = new SecretCache(fetcher, ids, nanos(refresh), nanos(staleness));
    ClientConfigTLS tls = profile.getTls();
    return new SecretsManagerPlugin(
        ids, cache, tls != null && !Boolean.TRUE.equals(tls.isDisabled()) ? tls : null);
  }

  private Duration refreshInterval() {
    if (refreshInterval != null) {
      if (refreshInterval.isNegative()) {
        throw new IllegalArgumentException("refreshInterval must not be negative");
      }
      return refreshInterval;
    }
    // Read from the process env rather than the config env, like
    // PARAMETERS_SECRETS_EXTENSION_HTTP_PORT, since this is Lambda runtime config shared with the
    // Lambda extension rather than Temporal client config
    String ttl = processEnv.get(ENV_SECRETS_MANAGER_TTL);
    if (ttl == null || ttl.isEmpty()) {
      return DEFAULT_REFRESH_INTERVAL;
    }
    if (WHOLE_NUMBER.matcher(ttl).matches()) {
      try {
        return Duration.ofSeconds(Long.parseLong(ttl));
      } catch (NumberFormatException e) {
        // Too large, handled below
      }
    }
    throw new IllegalArgumentException(
        "invalid "
            + ENV_SECRETS_MANAGER_TTL
            + " \""
            + ttl
            + "\", must be a non-negative whole number of seconds");
  }

  /** Nanoseconds, saturating rather than overflowing. */
  private static long nanos(Duration d) {
    try {
      return d.toNanos();
    } catch (ArithmeticException e) {
      return Long.MAX_VALUE;
    }
  }

  private static SecretCache.SecretIds loadSecretIds(
      ClientConfigProfile profile, @Nullable Map<String, String> env) {
    Map<String, String> fields = new HashMap<>();
    applyToml(fields, profile.getExt().get(EXTENSION_NAME));
    if (env != null) {
      // Like envconfig, set-but-empty env vars override (and so can unset) file values
      putEnv(fields, env, ENV_API_KEY_ID, "api_key");
      putEnv(fields, env, ENV_CLIENT_CERT_ID, "client_cert");
      putEnv(fields, env, ENV_CLIENT_KEY_ID, "client_key");
      putEnv(fields, env, ENV_SERVER_CA_CERT_ID, "server_ca_cert");
    }
    SecretCache.SecretIds ids =
        new SecretCache.SecretIds(
            fields.getOrDefault("api_key", ""),
            fields.getOrDefault("client_cert", ""),
            fields.getOrDefault("client_key", ""),
            fields.getOrDefault("server_ca_cert", ""));
    validate(ids, profile);
    return ids;
  }

  private static void putEnv(
      Map<String, String> fields, Map<String, String> env, String name, String field) {
    String value = env.get(name);
    if (value != null) {
      fields.put(field, value);
    }
  }

  private static void applyToml(Map<String, String> fields, @Nullable Map<String, Object> table) {
    if (table == null) {
      return;
    }
    for (Map.Entry<String, Object> e : table.entrySet()) {
      String key = e.getKey();
      if (key.equals("api_key_id")) {
        fields.put("api_key", tomlString(key, e.getValue()));
      } else if (key.equals("tls")) {
        if (!(e.getValue() instanceof Map)) {
          throw new IllegalArgumentException(
              "ext." + EXTENSION_NAME + ".tls must be a table, got " + typeName(e.getValue()));
        }
        for (Map.Entry<?, ?> tlsEntry : ((Map<?, ?>) e.getValue()).entrySet()) {
          String tlsKey = String.valueOf(tlsEntry.getKey());
          String field;
          switch (tlsKey) {
            case "client_cert_id":
              field = "client_cert";
              break;
            case "client_key_id":
              field = "client_key";
              break;
            case "server_ca_cert_id":
              field = "server_ca_cert";
              break;
            default:
              throw new IllegalArgumentException(
                  "unknown key \"" + tlsKey + "\" in ext." + EXTENSION_NAME + ".tls");
          }
          fields.put(field, tomlString("tls." + tlsKey, tlsEntry.getValue()));
        }
      } else {
        throw new IllegalArgumentException("unknown key \"" + key + "\" in ext." + EXTENSION_NAME);
      }
    }
  }

  private static String tomlString(String key, Object value) {
    if (!(value instanceof String)) {
      throw new IllegalArgumentException(
          "ext." + EXTENSION_NAME + "." + key + " must be a string, got " + typeName(value));
    }
    return (String) value;
  }

  private static String typeName(@Nullable Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof List) {
      return "array";
    }
    if (value instanceof Map) {
      return "table";
    }
    if (value instanceof Number) {
      return "number";
    }
    if (value instanceof Boolean) {
      return "boolean";
    }
    if (value instanceof String) {
      return "string";
    }
    return value.getClass().getSimpleName();
  }

  private static void validate(SecretCache.SecretIds ids, ClientConfigProfile profile) {
    List<String> conflicts = new ArrayList<>();
    if (!ids.apiKey.isEmpty() && !isEmpty(profile.getApiKey())) {
      conflicts.add("API key secret ID cannot be set with an API key");
    }
    ClientConfigTLS tls = profile.getTls();
    if (tls != null) {
      if (!ids.clientCert.isEmpty()
          && (!isEmpty(tls.getClientCertPath()) || !isEmpty(tls.getClientCertData()))) {
        conflicts.add("TLS client cert secret ID cannot be set with client cert path or data");
      }
      if (!ids.clientKey.isEmpty()
          && (!isEmpty(tls.getClientKeyPath()) || !isEmpty(tls.getClientKeyData()))) {
        conflicts.add("TLS client key secret ID cannot be set with client key path or data");
      }
      if (!ids.serverCaCert.isEmpty()
          && (!isEmpty(tls.getServerCACertPath()) || !isEmpty(tls.getServerCACertData()))) {
        conflicts.add(
            "TLS server CA cert secret ID cannot be set with server CA cert path or data");
      }
      if (ids.hasTls() && Boolean.TRUE.equals(tls.isDisabled())) {
        conflicts.add("TLS secret IDs cannot be set when TLS is disabled");
      }
    }
    if (ids.clientCert.isEmpty() != ids.clientKey.isEmpty()) {
      conflicts.add("TLS client cert and key secret IDs must be set together");
    }
    if (!conflicts.isEmpty()) {
      Collections.sort(conflicts);
      throw new IllegalArgumentException("invalid config: " + String.join("; ", conflicts));
    }
  }

  private static boolean isEmpty(@Nullable String s) {
    return s == null || s.isEmpty();
  }

  private static boolean isEmpty(@Nullable byte[] b) {
    return b == null || b.length == 0;
  }

  /** Builder for {@link SecretsManagerExtension}. */
  public static final class Builder {
    @Nullable private Object secretsClient;
    @Nullable private Duration refreshInterval;
    @Nullable private Duration maxStaleness;
    private Map<String, String> processEnv = System.getenv();

    private Builder() {}

    /**
     * Sets the Secrets Manager client used to fetch secrets. If unset, secrets are fetched through
     * the AWS Parameters and Secrets Lambda Extension if it is detected, and otherwise with a
     * client created from the default AWS config, which requires {@code
     * software.amazon.awssdk:secretsmanager} on the classpath.
     */
    public Builder setSecretsClient(@Nullable SecretsManagerClient secretsClient) {
      this.secretsClient = secretsClient;
      return this;
    }

    /**
     * Sets how long a fetched secret is used before it is refreshed. If unset, defaults to the
     * {@code SECRETS_MANAGER_TTL} env var (non-negative whole seconds, where 0 refreshes on every
     * use) as used by the AWS Parameters and Secrets Lambda Extension, or 5 minutes if that is
     * unset.
     */
    public Builder setRefreshInterval(@Nullable Duration refreshInterval) {
      this.refreshInterval = refreshInterval;
      return this;
    }

    /**
     * Sets how long after its last successful fetch a secret may still be used if refreshing it
     * fails. Defaults to 24 hours. Values less than the refresh interval are treated as the refresh
     * interval.
     */
    public Builder setMaxStaleness(@Nullable Duration maxStaleness) {
      this.maxStaleness = maxStaleness;
      return this;
    }

    /** Overrides the process env, for tests. */
    Builder setProcessEnv(Map<String, String> processEnv) {
      this.processEnv = Objects.requireNonNull(processEnv);
      return this;
    }

    public SecretsManagerExtension build() {
      return new SecretsManagerExtension(this);
    }
  }
}
