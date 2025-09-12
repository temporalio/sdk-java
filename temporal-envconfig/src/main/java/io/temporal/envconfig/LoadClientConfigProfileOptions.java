package io.temporal.envconfig;

import io.temporal.common.Experimental;
import java.util.Map;

//// LoadClientConfigProfileOptions are options for [LoadClientConfigProfile].
// type LoadClientConfigProfileOptions struct {
//    // Override the file path to use to load the TOML file for config. Defaults to
// TEMPORAL_CONFIG_FILE environment
//    // variable or if that is unset/empty, defaults to [os.UserConfigDir]/temporal/temporal.toml.
// If ConfigFileData is
//    // set, this cannot be set and no file loading from disk occurs. Ignored if DisableFile is
// true.
//    ConfigFilePath string
//
//    // TOML data to load for config. If set, this overrides any file loading. Cannot be set if
// ConfigFilePath is set.
//    // Ignored if DisableFile is true.
//    ConfigFileData []byte
//
//    // Specific profile to use after file is loaded. Defaults to TEMPORAL_PROFILE environment
// variable or if that is
//    // unset/empty, defaults to "default". If either this or the environment variable are set,
// load will fail if the
//    // profile isn't present in the config. Ignored if DisableFile is true.
//    ConfigFileProfile string
//
//    // If true, will error if there are unrecognized keys.
//    ConfigFileStrict bool
//
//    // If true, will not do any TOML loading from file or data. This and DisableEnv cannot both be
// true.
//    DisableFile bool
//
//    // If true, will not apply environment variables on top of file config for the client options,
// but
//    // TEMPORAL_CONFIG_FILE and TEMPORAL_PROFILE environment variables may still by used to
// populate defaults in this
//    // options structure.
//    DisableEnv bool
//
//    // Override the environment variable lookup. If nil, defaults to [EnvLookupOS].
//    EnvLookup EnvLookup
// }

@Experimental
public class LoadClientConfigProfileOptions {
  public static Builder newBuilder() {
    return new Builder();
  }

  private final String configFileProfile;
  private final String configFilePath;
  private final byte[] configFileData;
  private final boolean configFileStrict;
  private final boolean disableFile;
  private final boolean disableEnv;
  private final Map<String, String> envOverrides;

  public LoadClientConfigProfileOptions() {
    this("default", null, null, false, false, false, null);
  }

  public LoadClientConfigProfileOptions(
      String configFileProfile,
      String configFilePath,
      byte[] configFileData,
      boolean configFileStrict,
      boolean disableFile,
      boolean disableEnv,
      Map<String, String> envOverrides) {
    this.configFileProfile = configFileProfile;
    this.configFilePath = configFilePath;
    this.configFileData = configFileData;
    this.configFileStrict = configFileStrict;
    this.disableFile = disableFile;
    this.disableEnv = disableEnv;
    this.envOverrides = envOverrides;
  }

  public String getConfigFileProfile() {
    return configFileProfile;
  }

  public byte[] getConfigFileData() {
    return configFileData;
  }

  public boolean isConfigFileStrict() {
    return configFileStrict;
  }

  public boolean isDisableFile() {
    return disableFile;
  }

  public boolean isDisableEnv() {
    return disableEnv;
  }

  public Map<String, String> getEnvOverrides() {
    return envOverrides;
  }

  public String getConfigFilePath() {
    return configFilePath;
  }

  public static class Builder {
    private String configFileProfile;
    private String configFilePath;
    private byte[] configFileData;
    private boolean configFileStrict;
    private boolean disableFile;
    private boolean disableEnv;
    private Map<String, String> envOverrides;

    public LoadClientConfigProfileOptions build() {
      return new LoadClientConfigProfileOptions(
          configFileProfile,
          configFilePath,
          configFileData,
          configFileStrict,
          disableFile,
          disableEnv,
          envOverrides);
    }

    public Builder setConfigFileStrict(boolean configFileStrict) {
      this.configFileStrict = configFileStrict;
      return this;
    }

    public Builder setDisableFile(boolean disableFile) {
      this.disableFile = disableFile;
      return this;
    }

    public Builder setDisableEnv(boolean disableEnv) {
      this.disableEnv = disableEnv;
      return this;
    }

    public Builder setConfigFilePath(String configFilePath) {
      this.configFilePath = configFilePath;
      return this;
    }

    public Builder setConfigFileData(byte[] bytes) {
      this.configFileData = bytes;
      return this;
    }

    public Builder setConfigFileProfile(String foo) {
      this.configFileProfile = foo;
      return this;
    }

    public Builder setEnvOverrides(Map<String, String> envOverrides) {
      this.envOverrides = envOverrides;
      return this;
    }
  }
}
