package io.temporal.envconfig;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.grpc.Metadata;
import io.temporal.common.Experimental;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Classes for parsing the client config toml file.
 *
 * <p>These are package private, use {@link ClientConfig} and {@link ClientConfigProfile} to load
 * and work with client configs.
 */
@Experimental
class ClientConfigToml {
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  static class TomlClientConfig {
    @JsonProperty("profile")
    public Map<String, TomlClientConfigProfile> profiles;

    protected TomlClientConfig() {}

    TomlClientConfig(Map<String, TomlClientConfigProfile> profiles) {
      this.profiles = profiles;
    }
  }

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

  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  static class TomlClientConfigTLS {
    @JsonProperty("disabled")
    public Boolean disabled;

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
    public Boolean disableHostVerification;

    protected TomlClientConfigTLS() {}

    TomlClientConfigTLS(
        Boolean disabled,
        String clientCertPath,
        String clientCertData,
        String clientKeyPath,
        String clientKeyData,
        String serverCACertPath,
        String serverCACertData,
        String serverName,
        Boolean disableHostVerification) {
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

  @Nullable
  static ClientConfigTLS getClientConfigTLS(ClientConfigToml.TomlClientConfigProfile tomlProfile) {
    ClientConfigTLS tls = null;
    if (tomlProfile.tls != null) {
      tls =
          ClientConfigTLS.newBuilder()
              .setClientCertData(
                  tomlProfile.tls.clientCertData != null
                      ? tomlProfile.tls.clientCertData.getBytes(StandardCharsets.UTF_8)
                      : null)
              .setClientCertPath(tomlProfile.tls.clientCertPath)
              .setClientKeyData(
                  tomlProfile.tls.clientKeyData != null
                      ? tomlProfile.tls.clientKeyData.getBytes(StandardCharsets.UTF_8)
                      : null)
              .setClientKeyPath(tomlProfile.tls.clientKeyPath)
              .setServerCACertData(
                  tomlProfile.tls.serverCACertData != null
                      ? tomlProfile.tls.serverCACertData.getBytes(StandardCharsets.UTF_8)
                      : null)
              .setServerCACertPath(tomlProfile.tls.serverCACertPath)
              .setDisabled(tomlProfile.tls.disabled)
              .setServerName(tomlProfile.tls.serverName)
              .setDisableHostVerification(tomlProfile.tls.disableHostVerification)
              .build();
    }
    return tls;
  }

  private static String normalizeGrpcMetaKey(String key) {
    return key.toLowerCase().replace('_', '-');
  }

  static Map<String, ClientConfigProfile> getClientProfiles(
      ClientConfigToml.TomlClientConfig clientConfig) {
    Map<String, ClientConfigProfile> profiles = new HashMap<>(clientConfig.profiles.size());
    for (Map.Entry<String, ClientConfigToml.TomlClientConfigProfile> entry :
        clientConfig.profiles.entrySet()) {
      String profileName = entry.getKey();
      ClientConfigToml.TomlClientConfigProfile tomlProfile = entry.getValue();
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
    return profiles;
  }

  public static Map<String, TomlClientConfigProfile> fromClientProfiles(
      Map<String, ClientConfigProfile> profiles) {
    Map<String, TomlClientConfigProfile> tomlProfiles = new HashMap<>(profiles.size());
    for (Map.Entry<String, ClientConfigProfile> entry : profiles.entrySet()) {
      String profileName = entry.getKey();
      ClientConfigProfile profile = entry.getValue();
      TomlClientConfigTLS tls = null;
      if (profile.getTls() != null) {
        tls =
            new TomlClientConfigTLS(
                profile.getTls().isDisabled(),
                profile.getTls().getClientCertPath(),
                profile.getTls().getClientCertData() != null
                    ? new String(profile.getTls().getClientCertData(), StandardCharsets.UTF_8)
                    : null,
                profile.getTls().getClientKeyPath(),
                profile.getTls().getClientKeyData() != null
                    ? new String(profile.getTls().getClientKeyData(), StandardCharsets.UTF_8)
                    : null,
                profile.getTls().getServerCACertPath(),
                profile.getTls().getServerCACertData() != null
                    ? new String(profile.getTls().getServerCACertData(), StandardCharsets.UTF_8)
                    : null,
                profile.getTls().getServerName(),
                profile.getTls().isDisableHostVerification());
      }
      Map<String, String> grpcMeta = null;
      if (profile.getMetadata() != null) {
        grpcMeta = new HashMap<>();
        for (String key : profile.getMetadata().keys()) {
          Metadata.Key<String> metaKey = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
          Iterable<String> values = profile.getMetadata().getAll(metaKey);
          if (values != null) {
            // Join multiple values with comma
            String joinedValues = String.join(",", values);
            grpcMeta.put(key, joinedValues);
          }
        }
      }
      TomlClientConfigProfile tomlProfile =
          new TomlClientConfigProfile(
              profile.getAddress(), profile.getNamespace(), profile.getApiKey(), tls, grpcMeta);
      tomlProfiles.put(profileName, tomlProfile);
    }
    return tomlProfiles;
  }
}
