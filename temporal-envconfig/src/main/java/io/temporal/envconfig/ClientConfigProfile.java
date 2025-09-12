package io.temporal.envconfig;

import io.grpc.Metadata;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.Experimental;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/** ClientConfigProfile is profile-level configuration for a client. */
@Experimental
public class ClientConfigProfile {
  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ClientConfigProfile profile) {
    return new Builder(profile);
  }

  public static ClientConfigProfile getDefaultInstance() {
    return new Builder().build();
  }

  private String namespace;
  private String address;
  private String apiKey;
  private Metadata metadata;
  private ClientConfigTLS tls;

  private ClientConfigProfile(
      String namespace, String address, String apiKey, Metadata metadata, ClientConfigTLS tls) {
    this.namespace = namespace;
    this.address = address;
    this.apiKey = apiKey;
    this.metadata = metadata;
    this.tls = tls;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  public WorkflowServiceStubsOptions toWorkflowServiceStubsOptions() {
    WorkflowServiceStubsOptions.Builder builder = WorkflowServiceStubsOptions.newBuilder();
    if (this.address != null && !this.address.isEmpty()) {
      builder.setTarget(this.address);
    }
    if (this.apiKey != null && !this.apiKey.isEmpty()) {
      builder.addApiKey(() -> this.apiKey);
    }
    if (this.metadata != null) {
      builder.addGrpcMetadataProvider(() -> this.metadata);
    }
    if (this.tls != null && !this.tls.isDisabled()) {
      try {
        InputStream clientCertStream;
        if (this.tls.getClientCertPath() != null && !this.tls.getClientCertPath().isEmpty()) {
          clientCertStream = Files.newInputStream(Paths.get(this.tls.getClientCertPath()));
        } else if (this.tls.getClientCertData() != null
            && this.tls.getClientCertData().length > 0) {
          clientCertStream = new ByteArrayInputStream(this.tls.getClientCertData());
        } else {
          clientCertStream = null;
        }

        InputStream keyFile;
        if (this.tls.getClientKeyPath() != null && !this.tls.getClientKeyPath().isEmpty()) {
          keyFile = Files.newInputStream(Paths.get(this.tls.getClientKeyPath()));
        } else if (this.tls.getClientKeyData() != null && this.tls.getClientKeyData().length > 0) {
          keyFile = new ByteArrayInputStream(this.tls.getClientKeyData());
        } else {
          keyFile = null;
        }

        InputStream trustCertCollectionInputStream;
        if (this.tls.getServerCACertPath() != null && !this.tls.getServerCACertPath().isEmpty()) {
          trustCertCollectionInputStream =
              Files.newInputStream(Paths.get(this.tls.getServerCACertPath()));
        } else if (this.tls.getServerCACertData() != null
            && this.tls.getServerCACertData().length > 0) {
          trustCertCollectionInputStream = new ByteArrayInputStream(this.tls.getServerCACertData());
        } else {
          trustCertCollectionInputStream = null;
        }

        builder.setSslContext(
            SslContextBuilder.forClient()
                .trustManager(trustCertCollectionInputStream)
                .keyManager(clientCertStream, keyFile)
                .build());
      } catch (IOException e) {
        throw new RuntimeException("Unable to create SSL context", e);
      }
      if (this.tls.getServerName() != null && !this.tls.getServerName().isEmpty()) {
        builder.setChannelInitializer(c -> c.overrideAuthority(this.tls.getServerName()));
      }
    }

    return builder.build();
  }

  public WorkflowClientOptions toWorkflowClientOptions() {
    WorkflowClientOptions.Builder builder = WorkflowClientOptions.newBuilder();
    if (this.namespace != null && !this.namespace.isEmpty()) {
      builder.setNamespace(this.namespace);
    }

    return builder.build();
  }

  public static ClientConfigProfile load() throws IOException {
    return load(LoadClientConfigProfileOptions.newBuilder().build());
  }

  public static ClientConfigProfile load(LoadClientConfigProfileOptions options)
      throws IOException {
    if (options.isDisableFile() && options.isDisableEnv()) {
      throw new IllegalArgumentException("Cannot have both DisableFile and DisableEnv set to true");
    }
    Map<String, String> env = System.getenv();
    if (options.getEnvOverrides() != null) {
      env = options.getEnvOverrides();
    }
    ClientConfigProfile clientConfigProfile = ClientConfigProfile.getDefaultInstance();
    // If file is enabled, load it and find just the profile
    if (!options.isDisableFile()) {
      ClientConfig conf =
          ClientConfig.load(
              LoadClientConfigOptions.newBuilder()
                  .setConfigFilePath(options.getConfigFilePath())
                  .setConfigFileData(options.getConfigFileData())
                  .setStrictConfigFile(options.isConfigFileStrict())
                  .setEnvOverrides(options.getEnvOverrides())
                  .build());
      String profile = options.getConfigFileProfile();
      boolean profileUnset = false;
      if (profile == null || profile.isEmpty()) {
        profile = env.get("TEMPORAL_PROFILE");
      }
      if (profile == null || profile.isEmpty()) {
        profile = "default";
        profileUnset = true;
      }
      ClientConfigProfile fileClientConfigProfile = conf.getProfiles().get(profile);
      if (fileClientConfigProfile != null) {
        clientConfigProfile = fileClientConfigProfile;
      } else if (!profileUnset) {
        throw new IllegalArgumentException("Unable to find profile " + profile + " in config data");
      }
    }
    // If env is enabled, apply it on top of an empty profile
    if (!options.isDisableEnv()) {
      Map<String, String> overrideEnvVars = null;
      clientConfigProfile.applyEnvOverrides(overrideEnvVars);
    }
    return clientConfigProfile;
  }

  private void applyEnvOverrides(Map<String, String> overrideEnvVars) {
    Map<String, String> env = System.getenv();
    if (overrideEnvVars != null) {
      env = overrideEnvVars;
    }
    if (env.containsKey("TEMPORAL_ADDRESS")) {
      this.address = env.get("TEMPORAL_ADDRESS");
    }
    if (env.containsKey("TEMPORAL_NAMESPACE")) {
      this.namespace = env.get("TEMPORAL_NAMESPACE");
    }
    if (env.containsKey("TEMPORAL_API_KEY")) {
      this.apiKey = env.get("TEMPORAL_API_KEY");
    }
    // TLS settings
    ClientConfigTLS.Builder tlsBuilder = this.tls != null ? this.tls.toBuilder() : null;
    if (env.containsKey("TEMPORAL_TLS")) {
      String s = env.get("TEMPORAL_TLS");
      if (s != null && !s.isEmpty()) {
        boolean v = Boolean.parseBoolean(s);
        if (tlsBuilder == null) {
          tlsBuilder = ClientConfigTLS.newBuilder();
        }
        tlsBuilder.setDisabled(!v);
      }
    }
    if (env.containsKey("TEMPORAL_TLS_CLIENT_CERT_PATH")) {
      String s = env.get("TEMPORAL_TLS_CLIENT_CERT_PATH");
      if (s != null && !s.isEmpty()) {
        if (tlsBuilder == null) {
          tlsBuilder = ClientConfigTLS.newBuilder();
        }
        tlsBuilder.setClientCertPath(s);
      }
    }
    if (env.containsKey("TEMPORAL_TLS_CLIENT_CERT_DATA")) {
      String s = env.get("TEMPORAL_TLS_CLIENT_CERT_DATA");
      if (s != null && !s.isEmpty()) {
        if (tlsBuilder == null) {
          tlsBuilder = ClientConfigTLS.newBuilder();
        }
        tlsBuilder.setClientCertData(s.getBytes());
      }
    }
    if (env.containsKey("TEMPORAL_TLS_CLIENT_KEY_PATH")) {
      String s = env.get("TEMPORAL_TLS_CLIENT_KEY_PATH");
      if (s != null && !s.isEmpty()) {
        if (tlsBuilder == null) {
          tlsBuilder = ClientConfigTLS.newBuilder();
        }
        tlsBuilder.setClientKeyPath(s);
      }
    }
    if (env.containsKey("TEMPORAL_TLS_CLIENT_KEY_DATA")) {
      String s = env.get("TEMPORAL_TLS_CLIENT_KEY_DATA");
      if (s != null && !s.isEmpty()) {
        if (tlsBuilder == null) {
          tlsBuilder = ClientConfigTLS.newBuilder();
        }
        tlsBuilder.setClientKeyData(s.getBytes());
      }
    }
    if (env.containsKey("TEMPORAL_TLS_SERVER_CA_CERT_PATH")) {
      String s = env.get("TEMPORAL_TLS_SERVER_CA_CERT_PATH");
      if (s != null && !s.isEmpty()) {
        if (tlsBuilder == null) {
          tlsBuilder = ClientConfigTLS.newBuilder();
        }
        tlsBuilder.setServerCACertPath(s);
      }
    }
    if (env.containsKey("TEMPORAL_TLS_SERVER_CA_CERT_DATA")) {
      String s = env.get("TEMPORAL_TLS_SERVER_CA_CERT_DATA");
      if (s != null && !s.isEmpty()) {
        if (tlsBuilder == null) {
          tlsBuilder = ClientConfigTLS.newBuilder();
        }
        tlsBuilder.setServerCACertData(s.getBytes());
      }
    }
    if (env.containsKey("TEMPORAL_TLS_SERVER_NAME")) {
      String s = env.get("TEMPORAL_TLS_SERVER_NAME");
      if (tlsBuilder == null) {
        tlsBuilder = ClientConfigTLS.newBuilder();
      }
      tlsBuilder.setServerName(s);
    }
    if (env.containsKey("TEMPORAL_TLS_DISABLE_HOST_VERIFICATION")) {
      String s = env.get("TEMPORAL_TLS_DISABLE_HOST_VERIFICATION");
      if (s != null && !s.isEmpty()) {
        boolean v = Boolean.parseBoolean(s);
        if (tlsBuilder == null) {
          tlsBuilder = ClientConfigTLS.newBuilder();
        }
        // tlsBuilder.setDisableHostVerification(v);
      }
    }
    if (env.containsKey("TEMPORAL_TLS_SERVER_NAME")) {
      String s = env.get("TEMPORAL_TLS_SERVER_NAME");
      if (tlsBuilder == null) {
        tlsBuilder = ClientConfigTLS.newBuilder();
      }
      tlsBuilder.setServerName(s);
    }
    // Apply the TLS changes if any
    if (tlsBuilder != null) {
      this.tls = tlsBuilder.build();
    }
    // GRPC meta requires crawling the envs to find
    for (Map.Entry<String, String> entry : env.entrySet()) {
      String v = entry.getValue();
      if (entry.getKey().startsWith("TEMPORAL_GRPC_META_")) {
        String key = entry.getKey().substring("TEMPORAL_GRPC_META_".length());
        key = key.replace('_', '-').toLowerCase();
        if (this.metadata == null) {
          this.metadata = new Metadata();
        }
        Metadata.Key<String> metaKey = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
        // Empty env vars are not the same as unset. Unset will leave the
        // meta key unchanged, but empty removes it.
        if (v.isEmpty()) {
          this.metadata.removeAll(metaKey);
        } else {
          this.metadata.put(metaKey, v);
        }
      }
    }
  }

  public String getNamespace() {
    return namespace;
  }

  public String getAddress() {
    return address;
  }

  public String getApiKey() {
    return apiKey;
  }

  public Metadata getMetadata() {
    return metadata;
  }

  public ClientConfigTLS getTls() {
    return tls;
  }

  public static final class Builder {
    private String namespace;
    private String address;
    private String apiKey;
    private Metadata metadata;
    private ClientConfigTLS tls;

    public Builder() {}

    public Builder(ClientConfigProfile profile) {
      this.namespace = profile.namespace;
      this.address = profile.address;
      this.apiKey = profile.apiKey;
      this.metadata = profile.metadata;
      this.tls = profile.tls;
    }

    public Builder setNamespace(String namespace) {
      this.namespace = namespace;
      return this;
    }

    public Builder setAddress(String address) {
      this.address = address;
      return this;
    }

    public Builder setApiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    public Builder setMetadata(Metadata metadata) {
      this.metadata = metadata;
      return this;
    }

    public Builder setTls(ClientConfigTLS tls) {
      this.tls = tls;
      return this;
    }

    public ClientConfigProfile build() {
      return new ClientConfigProfile(namespace, address, apiKey, metadata, tls);
    }
  }
}
