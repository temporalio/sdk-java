package io.temporal.envconfig;

import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.io.*;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ClientConfig represents a client config file.
 *
 * <p>The default config file path is OS-specific:
 *
 * <ul>
 *   <li>macOS: $HOME/Library/Application Support/temporalio/temporal.toml
 *   <li>Windows: %APPDATA%\temporalio\temporal.toml
 *   <li>Linux/other: $HOME/.config/temporalio/temporal.toml
 * </ul>
 */
public class ClientConfig {
  /** Creates a new builder to build a {@link ClientConfig}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Creates a new builder to build a {@link ClientConfig} based on an existing config.
   *
   * @param profile the existing profile to base the builder on
   * @return a new Builder instance
   */
  public static Builder newBuilder(ClientConfig profile) {
    return new Builder(profile);
  }

  /** Returns a default instance of {@link ClientConfig} with all fields unset. */
  public static ClientConfig getDefaultInstance() {
    return new ClientConfig.Builder().build();
  }

  private static String getDefaultConfigFilePath() {
    String userDir = System.getProperty("user.home");
    if (userDir == null || userDir.isEmpty()) {
      throw new RuntimeException("failed getting user home directory");
    }
    return getDefaultConfigFilePath(userDir, System.getProperty("os.name"), System.getenv());
  }

  static String getDefaultConfigFilePath(
      String userDir, String osName, Map<String, String> environment) {
    if (osName != null) {
      String osNameLower = osName.toLowerCase();
      if (osNameLower.contains("mac")) {
        return Paths.get(userDir, "Library", "Application Support", "temporalio", "temporal.toml")
            .toString();
      }
      if (osNameLower.contains("win")) {
        String appData = environment != null ? environment.get("APPDATA") : null;
        if (appData == null || appData.isEmpty()) {
          throw new RuntimeException("%APPDATA% is not defined");
        }
        return Paths.get(appData, "temporalio", "temporal.toml").toString();
      }
    }
    return Paths.get(userDir, ".config", "temporalio", "temporal.toml").toString();
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
   *
   * @param options options to control loading the config
   * @throws IOException if the config file cannot be read or parsed
   */
  public static ClientConfig load(LoadClientConfigOptions options) throws IOException {
    ObjectReader reader = new TomlMapper().readerFor(ClientConfigToml.TomlClientConfig.class);
    if (options.isStrictConfigFile()) {
      reader =
          reader.withFeatures(
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    } else {
      reader =
          reader.withoutFeatures(
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
    if (options.getConfigFileData() != null && options.getConfigFileData().length > 0) {
      if (options.getConfigFilePath() != null && !options.getConfigFilePath().isEmpty()) {
        throw new IllegalArgumentException(
            "Cannot have both ConfigFileData and ConfigFilePath set");
      }
      ClientConfigToml.TomlClientConfig result = reader.readValue(options.getConfigFileData());
      return new ClientConfig(ClientConfigToml.getClientProfiles(result));
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
      try {
        ClientConfigToml.TomlClientConfig result = reader.readValue(new File(file));
        return new ClientConfig(ClientConfigToml.getClientProfiles(result));
      } catch (FileNotFoundException e) {
        // File not found is ok - return default empty config
        return getDefaultInstance();
      }
    }
  }

  /**
   * Load client config from given TOML data.
   *
   * @param tomlData TOML data to parse
   * @return the parsed client config
   * @throws IOException if the TOML data cannot be parsed
   */
  public static ClientConfig fromToml(byte[] tomlData) throws IOException {
    return fromToml(tomlData, ClientConfigFromTomlOptions.getDefaultInstance());
  }

  /**
   * Load client config from given TOML data.
   *
   * @param tomlData TOML data to parse
   * @param options options to control parsing the TOML data
   * @return the parsed client config
   * @throws IOException if the TOML data cannot be parsed
   */
  public static ClientConfig fromToml(byte[] tomlData, ClientConfigFromTomlOptions options)
      throws IOException {
    ObjectReader reader = new TomlMapper().readerFor(ClientConfigToml.TomlClientConfig.class);
    if (options.isStrictConfigFile()) {
      reader =
          reader.withFeatures(
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    } else {
      reader =
          reader.withoutFeatures(
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
    ClientConfigToml.TomlClientConfig result = reader.readValue(tomlData);
    return new ClientConfig(ClientConfigToml.getClientProfiles(result));
  }

  /**
   * Convert the client config to TOML data. Encoding is UTF-8.
   *
   * @param config the client config to convert
   * @return the TOML data as bytes
   * @apiNote The output will not be identical to the input if the config was loaded from a file
   *     because comments and formatting are not preserved.
   */
  public static byte[] toTomlAsBytes(ClientConfig config) throws IOException {
    ObjectWriter writer = new TomlMapper().writerFor(ClientConfigToml.TomlClientConfig.class);
    return writer.writeValueAsBytes(
        new ClientConfigToml.TomlClientConfig(
            ClientConfigToml.fromClientProfiles(config.getProfiles())));
  }

  private ClientConfig(Map<String, ClientConfigProfile> profiles) {
    this.profiles = profiles;
  }

  private final Map<String, ClientConfigProfile> profiles;

  /** All profiles loaded from the config file, may be empty but never null. */
  public Map<String, ClientConfigProfile> getProfiles() {
    return new HashMap<>(profiles);
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    ClientConfig that = (ClientConfig) o;
    return Objects.equals(profiles, that.profiles);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(profiles);
  }

  @Override
  public String toString() {
    return "ClientConfig{" + "profiles=" + profiles + '}';
  }

  public static final class Builder {
    private final Map<String, ClientConfigProfile> profiles;

    public Builder(ClientConfig config) {
      this.profiles = config.getProfiles();
    }

    public Builder() {
      this.profiles = new HashMap<>();
    }

    public Builder putProfile(String name, ClientConfigProfile profile) {
      profiles.put(name, profile);
      return this;
    }

    public ClientConfig build() {
      return new ClientConfig(profiles);
    }
  }
}
