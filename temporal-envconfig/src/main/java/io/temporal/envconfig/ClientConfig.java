package io.temporal.envconfig;

import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import io.temporal.common.Experimental;
import java.io.*;
import java.util.Map;

/** ClientConfig represents a client config file. */
@Experimental
public class ClientConfig {

  /** Get the default config file path: $HOME/.config/temporal/temporal.toml */
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
      ClientConfigToml.TomlClientConfig result = reader.readValue(new File(file));
      return new ClientConfig(ClientConfigToml.getClientProfiles(result));
    }
  }

  public ClientConfig(Map<String, ClientConfigProfile> profiles) {
    this.profiles = profiles;
  }

  private final Map<String, ClientConfigProfile> profiles;

  /** All profiles loaded from the config file, may be empty but never null. */
  public Map<String, ClientConfigProfile> getProfiles() {
    return profiles;
  }
}
