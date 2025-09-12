package io.temporal.envconfig;

import io.temporal.common.Experimental;
import java.util.Map;

// type LoadClientConfigOptions struct {
//    // Override the file path to use to load the TOML file for config. Defaults to
// TEMPORAL_CONFIG_FILE environment
//    // variable or if that is unset/empty, defaults to [os.UserConfigDir]/temporal/temporal.toml.
// If ConfigFileData is
//    // set, this cannot be set and no file loading from disk occurs.
//    ConfigFilePath string
//
//    // TOML data to load for config. If set, this overrides any file loading. Cannot be set if
// ConfigFilePath is set.
//    ConfigFileData []byte
//
//    // If true, will error if there are unrecognized keys.
//    ConfigFileStrict bool
//
//    // Override the environment variable lookup (only used to determine which config file to
// load). If nil,
//    // defaults to [EnvLookupOS].
//    EnvLookup EnvLookup
// }
@Experimental
public class LoadClientConfigOptions {
  public static Builder newBuilder() {
    return new Builder();
  }

  private final String configFilePath;
  private final byte[] configFileData;
  private final boolean strictConfigFile;
  private final Map<String, String> envOverrides;

  public LoadClientConfigOptions(
      String configFilePath,
      byte[] configFileData,
      boolean strictConfigFile,
      Map<String, String> envOverrides) {
    this.configFilePath = configFilePath;
    this.configFileData = configFileData;
    this.strictConfigFile = strictConfigFile;
    this.envOverrides = envOverrides;
  }

  public String getConfigFilePath() {
    return configFilePath;
  }

  public byte[] getConfigFileData() {
    return configFileData;
  }

  public boolean isStrictConfigFile() {
    return strictConfigFile;
  }

  public Map<String, String> getEnvOverrides() {
    return envOverrides;
  }

  public static class Builder {
    private String configFilePath;
    private byte[] configFileData;
    private boolean strictConfigFile;
    private Map<String, String> envOverrides;

    public Builder() {}

    public LoadClientConfigOptions build() {
      return new LoadClientConfigOptions(
          configFilePath, configFileData, strictConfigFile, envOverrides);
    }

    public Builder setStrictConfigFile(boolean configFileStrict) {
      this.strictConfigFile = configFileStrict;
      return this;
    }

    public Builder setEnvOverrides(Map<String, String> envOverrides) {
      this.envOverrides = envOverrides;
      return this;
    }

    public Builder setConfigFileData(byte[] bytes) {
      this.configFileData = bytes;
      return this;
    }

    public Builder setConfigFilePath(String configFilePath) {
      this.configFilePath = configFilePath;
      return this;
    }
  }
}
