package io.temporal.envconfig;

import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.Experimental;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.serviceclient.WorkflowServiceStubsPlugin;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** ClientConfigProfile is profile-level configuration for a client. */
public class ClientConfigProfile {
  /** Creates a new builder to build a {@link ClientConfigProfile}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Creates a new builder to build a {@link ClientConfigProfile} based on an existing profile.
   *
   * @param profile the existing profile to base the builder on
   * @return a new Builder instance
   */
  public static Builder newBuilder(ClientConfigProfile profile) {
    return new Builder(profile);
  }

  /** Returns a default instance of {@link ClientConfigProfile} with all fields unset. */
  public static ClientConfigProfile getDefaultInstance() {
    return new Builder().build();
  }

  /**
   * Load a client profile from given sources, applying environment variable overrides.
   *
   * <p>This is a convenience method for {@link #load(LoadClientConfigProfileOptions)} with default
   * options.
   *
   * @throws IOException if the config file cannot be read or parsed
   */
  public static ClientConfigProfile load() throws IOException {
    return load(LoadClientConfigProfileOptions.newBuilder().build());
  }

  /**
   * Load a client profile from given sources, applying environment variable overrides.
   *
   * <p>If {@link LoadClientConfigProfileOptions#getExtensions() extensions} are set, each is then
   * called in order with the loaded profile, and any plugin it returns is added to the returned
   * profile's {@link #getPlugins() plugins}.
   *
   * @param options options to control loading the profile
   * @throws IOException if the config file cannot be read or parsed, or an extension fails with an
   *     {@link IOException}
   */
  public static ClientConfigProfile load(LoadClientConfigProfileOptions options)
      throws IOException {
    if (options.isDisableFile() && options.isDisableEnv()) {
      throw new IllegalArgumentException("Cannot have both DisableFile and DisableEnv set to true");
    }
    validateExtensions(options.getExtensions());
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
      clientConfigProfile.applyEnvOverrides(options.getEnvOverrides());
    }
    if (!options.getExtensions().isEmpty()) {
      clientConfigProfile =
          applyExtensions(
              clientConfigProfile, options.getExtensions(), options.isDisableEnv() ? null : env);
    }
    return clientConfigProfile;
  }

  private static void validateExtensions(List<ClientConfigExtension> extensions) {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < extensions.size(); i++) {
      ClientConfigExtension extension = extensions.get(i);
      if (extension == null) {
        throw new IllegalArgumentException("Client config extension at index " + i + " is null");
      }
      String name = extension.getName();
      if (name == null || name.isEmpty()) {
        throw new IllegalArgumentException(
            "Client config extension at index "
                + i
                + " ("
                + extension.getClass().getName()
                + ") has empty name");
      }
      if (!seen.add(name)) {
        throw new IllegalArgumentException(
            "Duplicate client config extension name \"" + name + "\"");
      }
    }
  }

  private static ClientConfigProfile applyExtensions(
      ClientConfigProfile profile, List<ClientConfigExtension> extensions, Map<String, String> env)
      throws IOException {
    ClientConfigExtensionRequest request =
        ClientConfigExtensionRequest.newBuilder().setProfile(profile).setEnv(env).build();
    List<WorkflowServiceStubsPlugin> plugins = new ArrayList<>(profile.plugins);
    for (ClientConfigExtension extension : extensions) {
      WorkflowServiceStubsPlugin plugin;
      try {
        plugin = extension.getClientPlugin(request);
      } catch (IOException e) {
        throw new IOException(extensionErrorMessage(extension, e), e);
      } catch (RuntimeException e) {
        throw new RuntimeException(extensionErrorMessage(extension, e), e);
      }
      if (plugin != null) {
        plugins.add(plugin);
      }
    }
    return profile.toBuilder().setPlugins(plugins).build();
  }

  private static String extensionErrorMessage(ClientConfigExtension extension, Exception e) {
    return "Client config extension \"" + extension.getName() + "\" failed: " + e.getMessage();
  }

  private String namespace;
  private String address;
  private String apiKey;
  private Metadata metadata;
  private ClientConfigTLS tls;
  private final Map<String, Map<String, Object>> ext;
  // Unlike the other fields, plugins are not loaded config but runtime objects returned by
  // extensions during load. They're stored here because the profile is the only thing carried from
  // load() to toWorkflowServiceStubsOptions().
  private final List<WorkflowServiceStubsPlugin> plugins;

  private ClientConfigProfile(
      String namespace,
      String address,
      String apiKey,
      Metadata metadata,
      ClientConfigTLS tls,
      Map<String, Map<String, Object>> ext,
      List<WorkflowServiceStubsPlugin> plugins) {
    this.namespace = namespace;
    this.address = address;
    this.apiKey = apiKey;
    this.metadata = metadata;
    this.tls = tls;
    this.ext = ext;
    this.plugins = plugins;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  /**
   * Converts this profile to WorkflowServiceStubsOptions. Note that not all fields are converted,
   * only those relevant to service stubs.
   *
   * <p>The profile's {@link #getPlugins() plugins}, such as those returned by extensions, are set
   * as the options' plugins. Plugins that also implement {@link
   * io.temporal.client.WorkflowClientPlugin} are propagated to a workflow client created from the
   * resulting service stubs.
   */
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
    if (this.tls != null && (this.tls.isDisabled() == null || !this.tls.isDisabled())) {
      InputStream clientCertStream = null;
      InputStream keyFileStream = null;
      InputStream trustCertCollectionInputStream = null;
      try {
        if (this.tls.getClientCertPath() != null && !this.tls.getClientCertPath().isEmpty()) {
          clientCertStream = Files.newInputStream(Paths.get(this.tls.getClientCertPath()));
        } else if (this.tls.getClientCertData() != null
            && this.tls.getClientCertData().length > 0) {
          clientCertStream = new ByteArrayInputStream(this.tls.getClientCertData());
        }

        if (this.tls.getClientKeyPath() != null && !this.tls.getClientKeyPath().isEmpty()) {
          keyFileStream = Files.newInputStream(Paths.get(this.tls.getClientKeyPath()));
        } else if (this.tls.getClientKeyData() != null && this.tls.getClientKeyData().length > 0) {
          keyFileStream = new ByteArrayInputStream(this.tls.getClientKeyData());
        }

        if (this.tls.getServerCACertPath() != null && !this.tls.getServerCACertPath().isEmpty()) {
          trustCertCollectionInputStream =
              Files.newInputStream(Paths.get(this.tls.getServerCACertPath()));
        } else if (this.tls.getServerCACertData() != null
            && this.tls.getServerCACertData().length > 0) {
          trustCertCollectionInputStream = new ByteArrayInputStream(this.tls.getServerCACertData());
        }

        SslContextBuilder sslContextBuilder = GrpcSslContexts.forClient();
        if (trustCertCollectionInputStream != null) {
          sslContextBuilder.trustManager(trustCertCollectionInputStream);
        } else if (Boolean.TRUE.equals(this.tls.isDisableHostVerification())) {
          sslContextBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
        }
        sslContextBuilder.keyManager(clientCertStream, keyFileStream);
        builder.setSslContext(sslContextBuilder.build());
      } catch (IOException e) {
        throw new RuntimeException("Unable to create SSL context", e);
      } finally {
        if (clientCertStream != null) {
          try {
            clientCertStream.close();
          } catch (IOException e) {
            // Ignore
          }
        }
        if (keyFileStream != null) {
          try {
            keyFileStream.close();
          } catch (IOException e) {
            // Ignore
          }
        }
        if (trustCertCollectionInputStream != null) {
          try {
            trustCertCollectionInputStream.close();
          } catch (IOException e) {
            // Ignore
          }
        }
      }
      if (this.tls.getServerName() != null && !this.tls.getServerName().isEmpty()) {
        builder.setChannelInitializer(c -> c.overrideAuthority(this.tls.getServerName()));
      }
    } else if (this.apiKey != null && !this.apiKey.isEmpty()) {
      if (this.getTls() == null
          || this.tls.isDisabled() == null
          || (this.tls.isDisabled() != null && !this.tls.isDisabled())) {
        // If API key is set, TLS is required, so enable it with defaults
        builder.setEnableHttps(true);
      }
    }
    if (!this.plugins.isEmpty()) {
      builder.setPlugins(this.plugins.toArray(new WorkflowServiceStubsPlugin[0]));
    }

    return builder.build();
  }

  /**
   * Converts this profile to WorkflowClientOptions. Note that not all fields are converted, only
   * those relevant to client options.
   *
   * <p>The profile's {@link #getPlugins() plugins} are not included here, since they are propagated
   * from the service stubs created with {@link #toWorkflowServiceStubsOptions()}. Including them
   * here as well would register them twice when both conversions are used, which is the usual case.
   */
  public WorkflowClientOptions toWorkflowClientOptions() {
    WorkflowClientOptions.Builder builder = WorkflowClientOptions.newBuilder();
    if (this.namespace != null && !this.namespace.isEmpty()) {
      builder.setNamespace(this.namespace);
    }

    return builder.build();
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
        tlsBuilder.setDisableHostVerification(v);
      }
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

  /**
   * Raw extension config, keyed by extension name, never null. Loaded from TOML tables at {@code
   * profile.<profile>.ext.<name>}, which are not interpreted or validated (even in strict mode).
   * Each {@link ClientConfigExtension} decodes its own entry. Values are untyped TOML values (e.g.
   * {@link String}, {@link Number}, {@link Boolean}, {@link List}, {@link Map}). Unmodifiable.
   */
  @Experimental
  public Map<String, Map<String, Object>> getExt() {
    return ext;
  }

  /**
   * Plugins to include in {@link #toWorkflowServiceStubsOptions()}, never null. Populated by {@link
   * ClientConfigExtension extensions} when loading the profile. Unmodifiable.
   */
  @Experimental
  public List<WorkflowServiceStubsPlugin> getPlugins() {
    return plugins;
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    ClientConfigProfile profile = (ClientConfigProfile) o;
    // Compare metadata properly
    if (this.metadata == null) {
      if (profile.metadata != null && profile.metadata.keys() != null) {
        return false;
      }
    } else if (profile.metadata == null) {
      if (this.metadata.keys() != null) {
        return false;
      }
    } else {
      // Both non-null, compare keys and values
      if (!Objects.equals(this.metadata.keys(), profile.metadata.keys())) {
        return false;
      }
      for (String key : this.metadata.keys()) {
        if (!Objects.equals(
            this.metadata.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)),
            profile.metadata.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)))) {
          return false;
        }
      }
    }
    return Objects.equals(namespace, profile.namespace)
        && Objects.equals(address, profile.address)
        && Objects.equals(apiKey, profile.apiKey)
        && Objects.equals(tls, profile.tls)
        && Objects.equals(ext, profile.ext)
        && Objects.equals(plugins, profile.plugins);
  }

  @Override
  public int hashCode() {
    return Objects.hash(namespace, address, apiKey, metadata, tls, ext, plugins);
  }

  @Override
  public String toString() {
    return "ClientConfigProfile{"
        + "namespace='"
        + namespace
        + '\''
        + ", address='"
        + address
        + '\''
        + ", apiKey='"
        + apiKey
        + '\''
        + ", metadata="
        + metadata
        + ", tls="
        + tls
        + ", ext="
        + ext
        + ", plugins="
        + plugins
        + '}';
  }

  public static final class Builder {
    private String namespace;
    private String address;
    private String apiKey;
    private Metadata metadata;
    private ClientConfigTLS tls;
    private Map<String, Map<String, Object>> ext;
    private List<WorkflowServiceStubsPlugin> plugins;

    private Builder() {}

    private Builder(ClientConfigProfile profile) {
      this.namespace = profile.namespace;
      this.address = profile.address;
      this.apiKey = profile.apiKey;
      this.metadata = profile.metadata;
      this.tls = profile.tls;
      this.ext = profile.ext;
      this.plugins = profile.plugins;
    }

    /**
     * Sets the namespace for the client. This is optional; if not set, the default namespace will
     * be used.
     */
    public Builder setNamespace(String namespace) {
      this.namespace = namespace;
      return this;
    }

    /**
     * Sets the address of the Temporal service endpoint. This is optional; if not set, the default
     * address will be used.
     */
    public Builder setAddress(String address) {
      this.address = address;
      return this;
    }

    /** Sets the API key for the client. This is optional; if not set, no API key will be used. */
    public Builder setApiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /**
     * Sets the gRPC metadata to be sent with each request. This is optional; if not set, no
     * metadata will be sent.
     */
    public Builder setMetadata(Metadata metadata) {
      this.metadata = metadata;
      return this;
    }

    /**
     * Sets the TLS configuration for the client. This is optional; if not set, no TLS will be used.
     */
    public Builder setTls(ClientConfigTLS tls) {
      this.tls = tls;
      return this;
    }

    /**
     * Sets the raw extension config, keyed by extension name. This is optional; if not set, there
     * is no extension config. The config is copied, see {@link ClientConfigProfile#getExt()}.
     */
    @Experimental
    public Builder setExt(Map<String, Map<String, Object>> ext) {
      this.ext = ext;
      return this;
    }

    /**
     * Sets the plugins to include in {@link ClientConfigProfile#toWorkflowServiceStubsOptions()}.
     * This is optional; if not set, no plugins will be included. This is usually populated by
     * {@link ClientConfigExtension extensions} when loading the profile.
     */
    @Experimental
    public Builder setPlugins(List<WorkflowServiceStubsPlugin> plugins) {
      this.plugins = plugins;
      return this;
    }

    public ClientConfigProfile build() {
      return new ClientConfigProfile(
          namespace,
          address,
          apiKey,
          metadata,
          tls,
          copyExt(ext),
          plugins == null
              ? Collections.emptyList()
              : Collections.unmodifiableList(new ArrayList<>(plugins)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> copyExt(Map<String, Map<String, Object>> ext) {
      if (ext == null || ext.isEmpty()) {
        return Collections.emptyMap();
      }
      return (Map<String, Map<String, Object>>) (Map<String, ?>) copyValue(ext);
    }

    // Deep unmodifiable copy of an untyped TOML value
    private static Object copyValue(Object value) {
      if (value instanceof Map) {
        Map<?, ?> map = (Map<?, ?>) value;
        Map<Object, Object> copy = new LinkedHashMap<>(map.size());
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          copy.put(entry.getKey(), copyValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
      } else if (value instanceof List) {
        List<?> list = (List<?>) value;
        List<Object> copy = new ArrayList<>(list.size());
        for (Object item : list) {
          copy.add(copyValue(item));
        }
        return Collections.unmodifiableList(copy);
      }
      return value;
    }
  }
}
