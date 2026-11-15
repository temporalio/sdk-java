package io.temporal.aws.secretsmanager;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.temporal.authorization.AuthorizationGrpcMetadataProvider;
import io.temporal.envconfig.ClientConfigTLS;
import io.temporal.serviceclient.GrpcMetadataProvider;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.serviceclient.WorkflowServiceStubsPlugin;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Fetches the configured secrets when service stubs are created, and applies them to the stubs'
 * options.
 */
final class SecretsManagerPlugin implements WorkflowServiceStubsPlugin {
  static final String NAME = "io.temporal.aws.secretsmanager";

  final SecretCache.SecretIds ids;
  final SecretCache cache;

  /**
   * The profile's TLS config if TLS isn't disabled, for which envconfig created an SSL context that
   * this plugin replaces, or null.
   */
  @Nullable private final ClientConfigTLS profileTls;

  /**
   * The API key is refreshed at most this often, and retried this long after a failed refresh.
   * Checking more often would only be cache hits, and with a refresh interval of 0 (no caching),
   * this bounds the fetch rate. Overridable in tests.
   */
  volatile long minRefreshIntervalNanos = SecretCache.FAILED_REFRESH_RETRY_INTERVAL_NANOS;

  /**
   * Telemetry for the final options of the most recently created stubs. Read when used rather than
   * captured, since the final options are only seen in {@link #connectServiceClient}, after {@link
   * #configureServiceStubs} installs the API key supplier.
   */
  private volatile Telemetry telemetry = new Telemetry(null);

  SecretsManagerPlugin(
      SecretCache.SecretIds ids, SecretCache cache, @Nullable ClientConfigTLS profileTls) {
    this.ids = ids;
    this.cache = cache;
    this.profileTls = profileTls;
  }

  @Nonnull
  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public void configureServiceStubs(@Nonnull WorkflowServiceStubsOptions.Builder builder) {
    WorkflowServiceStubsOptions current = builder.build();

    SecretCache.CacheEntry<String> apiKey = cache.apiKey;
    if (apiKey != null) {
      Collection<GrpcMetadataProvider> providers = current.getGrpcMetadataProviders();
      if (providers != null) {
        for (GrpcMetadataProvider provider : providers) {
          if (provider instanceof AuthorizationGrpcMetadataProvider) {
            throw new IllegalStateException(
                "cannot use API key from AWS Secrets Manager, API key already set");
          }
        }
      }
      // Called for each request, so a refreshed API key is used without waiting for the refresh.
      // Like an API key from envconfig, this enables TLS unless it's explicitly configured.
      builder.addApiKey(() -> cache.getNow(apiKey, minRefreshIntervalNanos, telemetry));
    }

    if (ids.hasTls()) {
      if (profileTls == null && current.getSslContext() != null) {
        throw new IllegalStateException(
            "cannot use TLS config from AWS Secrets Manager, SSL context already set");
      }
      // The SSL context must be set on the options, so these fetches can't wait for
      // connectServiceClient and only report to a metrics scope that's already set
      builder.setSslContext(sslContext(new Telemetry(current.getMetricsScope())));
    }
  }

  @Nonnull
  @Override
  public WorkflowServiceStubs connectServiceClient(
      @Nonnull WorkflowServiceStubsOptions options, @Nonnull Supplier<WorkflowServiceStubs> next) {
    // Unlike in configureServiceStubs, these are the final options, after all plugins have
    // configured them and defaults are applied
    Telemetry tel = new Telemetry(options.getMetricsScope());
    telemetry = tel;
    // Fetched now so that creating the stubs fails on a bad secret instead of the first call
    if (cache.apiKey != null) {
      cache.get(cache.apiKey, tel);
    }
    return next.get();
  }

  /**
   * Creates an SSL context from the secrets and the profile's other TLS settings, the same way
   * envconfig does from the profile alone.
   */
  private SslContext sslContext(Telemetry tel) {
    SslContextBuilder builder = GrpcSslContexts.forClient();
    try {
      if (cache.serverCaCert != null) {
        builder.trustManager(cache.get(cache.serverCaCert, tel));
      } else {
        try (InputStream ca =
            profileTls != null
                ? open(profileTls.getServerCACertPath(), profileTls.getServerCACertData())
                : null) {
          if (ca != null) {
            builder.trustManager(ca);
          } else if (profileTls != null
              && Boolean.TRUE.equals(profileTls.isDisableHostVerification())) {
            builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
          }
        }
      }

      if (cache.clientCert != null) {
        SecretCache.ClientCertPair pair = cache.get(cache.clientCert, tel);
        builder.keyManager(pair.key, pair.chain);
      } else if (profileTls != null) {
        try (InputStream cert =
                open(profileTls.getClientCertPath(), profileTls.getClientCertData());
            InputStream key = open(profileTls.getClientKeyPath(), profileTls.getClientKeyData())) {
          if (cert != null && key != null) {
            builder.keyManager(cert, key);
          }
        }
      }
      return builder.build();
    } catch (IOException e) {
      throw new RuntimeException("Unable to create SSL context", e);
    }
  }

  @Nullable
  private static InputStream open(@Nullable String path, @Nullable byte[] data) throws IOException {
    if (path != null && !path.isEmpty()) {
      return Files.newInputStream(Paths.get(path));
    }
    if (data != null && data.length > 0) {
      return new ByteArrayInputStream(data);
    }
    return null;
  }
}
