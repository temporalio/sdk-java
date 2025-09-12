package io.temporal.envconfig;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import io.grpc.Metadata;
import io.temporal.common.Experimental;
import java.io.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

@Experimental
public class ClientConfig {

  private static String getDefaultConfigFilePath() {
    String userDir = System.getProperty("user.home");
    if (userDir == null || userDir.isEmpty()) {
      throw new RuntimeException("failed getting user home directory");
    }
    return userDir + "/.config/temporal/temporal.toml";
  }

  /**
   * Load all client profiles from given sources.
   *
   * <p>This does not apply environment variable overrides to the profiles, it only uses an
   * environment variable to find the default config file path (TEMPORAL_CONFIG_FILE). To get a
   * single profile with environment variables applied, use {@link ClientConfigProfile#load}.
   */
  public static ClientConfig load() throws IOException {
    return load(LoadClientConfigOptions.newBuilder().build());
  }

  /**
   * Load all client profiles from given sources.
   *
   * <p>This does not apply environment variable overrides to the profiles, it only uses an
   * environment variable to find the default config file path (TEMPORAL_CONFIG_FILE). To get a
   * single profile with environment variables applied, use {@link ClientConfigProfile#load}.
   */
  public static ClientConfig load(LoadClientConfigOptions options) throws IOException {
    ObjectReader reader = new TomlMapper().readerFor(TomlClientConfig.class);
    if (options.isStrictConfigFile()) {
      reader =
          reader.withFeatures(
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
    if (options.getConfigFileData() != null && options.getConfigFileData().length > 0) {
      if (options.getConfigFilePath() != null && !options.getConfigFilePath().isEmpty()) {
        throw new IllegalArgumentException(
            "Cannot have both ConfigFileData and ConfigFilePath set");
      }
      TomlClientConfig result = reader.readValue(options.getConfigFileData());
      return new ClientConfig(result);
    } else {
      // Get file name which is either set value, env var, or default path
      String file = options.getConfigFilePath();
      if (file == null || file.isEmpty()) {
        Map<String, String> env = options.getEnvOverrides();
        if (env == null) {
          env = System.getenv();
        }
        // Unlike env vars for the config values, empty and unset env var
        // for config file path are both treated as unset
        file = env.get("TEMPORAL_CONFIG_FILE");
      }
      if (file == null || file.isEmpty()) {
        file = getDefaultConfigFilePath();
      }
      TomlClientConfig result = reader.readValue(file);
      return new ClientConfig(result);
    }
  }

  private final Map<String, ClientConfigProfile> profiles;

  private ClientConfig() {
    profiles = Collections.emptyMap();
  }

  private String normalizeGrpcMetaKey(String key) {
    return key.toLowerCase().replace('_', '-');
  }

  private ClientConfig(TomlClientConfig clientConfig) {
    profiles = new HashMap<>(clientConfig.profiles.size());
    for (Map.Entry<String, TomlClientConfigProfile> entry : clientConfig.profiles.entrySet()) {
      String profileName = entry.getKey();
      TomlClientConfigProfile tomlProfile = entry.getValue();
      ClientConfigTLS tls = getClientConfigTLS(tomlProfile);
      Metadata metadata = null;
      if (tomlProfile.grpcMeta != null) {
        for (Map.Entry<String, String> metaEntry : tomlProfile.grpcMeta.entrySet()) {
          if (metadata == null) {
            metadata = new Metadata();
          }
          Metadata.Key<String> key =
              Metadata.Key.of(
                  normalizeGrpcMetaKey(metaEntry.getKey()), Metadata.ASCII_STRING_MARSHALLER);
          metadata.put(key, metaEntry.getValue());
        }
      }
      ClientConfigProfile profile =
          ClientConfigProfile.newBuilder()
              .setAddress(tomlProfile.address)
              .setNamespace(tomlProfile.namespace)
              .setApiKey(tomlProfile.apiKey)
              .setMetadata(metadata)
              .setTls(tls)
              .build();
      profiles.put(profileName, profile);
    }
  }

  @Nullable
  private static ClientConfigTLS getClientConfigTLS(TomlClientConfigProfile tomlProfile) {
    ClientConfigTLS tls = null;
    if (tomlProfile.tls != null) {
      tls =
          new ClientConfigTLS(
              tomlProfile.tls.disabled,
              tomlProfile.tls.clientCertPath,
              tomlProfile.tls.clientCertData.getBytes(),
              tomlProfile.tls.clientKeyPath,
              tomlProfile.tls.clientKeyData.getBytes(),
              tomlProfile.tls.serverCACertPath,
              tomlProfile.tls.serverCACertData.getBytes(),
              tomlProfile.tls.serverName,
              tomlProfile.tls.disableHostVerification);
    }
    return tls;
  }

  public Map<String, ClientConfigProfile> getProfiles() {
    return profiles;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  static class TomlClientConfig {
    @JsonProperty("profile")
    public Map<String, TomlClientConfigProfile> profiles;

    protected TomlClientConfig() {}

    TomlClientConfig(Map<String, TomlClientConfigProfile> profiles) {
      this.profiles = profiles;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  static class TomlClientConfigProfile {
    @JsonProperty("address")
    public String address;

    @JsonProperty("namespace")
    public String namespace;

    @JsonProperty("api_key")
    public String apiKey;

    @JsonProperty("tls")
    public TomlClientConfigTLS tls;

    @JsonProperty("grpc_meta")
    public Map<String, String> grpcMeta;

    protected TomlClientConfigProfile() {}

    TomlClientConfigProfile(
        String address,
        String namespace,
        String apiKey,
        TomlClientConfigTLS tls,
        Map<String, String> grpcMeta) {
      this.address = address;
      this.namespace = namespace;
      this.apiKey = apiKey;
      this.tls = tls;
      this.grpcMeta = grpcMeta;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  static class TomlClientConfigTLS {
    @JsonProperty("disabled")
    public boolean disabled;

    @JsonProperty("client_cert_path")
    public String clientCertPath;

    @JsonProperty("client_cert_data")
    public String clientCertData;

    @JsonProperty("client_key_path")
    public String clientKeyPath;

    @JsonProperty("client_key_data")
    public String clientKeyData;

    @JsonProperty("server_ca_cert_path")
    public String serverCACertPath;

    @JsonProperty("server_ca_cert_data")
    public String serverCACertData;

    @JsonProperty("server_name")
    public String serverName;

    @JsonProperty("disable_host_verification")
    public boolean disableHostVerification;

    protected TomlClientConfigTLS() {}

    TomlClientConfigTLS(
        boolean disabled,
        String clientCertPath,
        String clientCertData,
        String clientKeyPath,
        String clientKeyData,
        String serverCACertPath,
        String serverCACertData,
        String serverName,
        boolean disableHostVerification) {
      this.disabled = disabled;
      this.clientCertPath = clientCertPath;
      this.clientCertData = clientCertData;
      this.clientKeyPath = clientKeyPath;
      this.clientKeyData = clientKeyData;
      this.serverCACertPath = serverCACertPath;
      this.serverCACertData = serverCACertData;
      this.serverName = serverName;
      this.disableHostVerification = disableHostVerification;
    }
  }
}
