package io.temporal.envconfig;

import io.temporal.common.Experimental;
import java.util.Map;

/** Options for loading a client config via {@link ClientConfig#load(LoadClientConfigOptions)} */
@Experimental
public class LoadClientConfigOptions {
  /** Create a builder for {@link LoadClientConfigOptions}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** Create a builder from an existing {@link LoadClientConfigOptions}. */
  public static Builder newBuilder(LoadClientConfigOptions options) {
    return new Builder(options);
  }

  /** Returns a default instance of {@link LoadClientConfigOptions} with all fields unset. */
  public static LoadClientConfigOptions getDefaultInstance() {
    return new Builder().build();
  }

  private final String configFilePath;
  private final byte[] configFileData;
  private final boolean strictConfigFile;
  private final Map<String, String> envOverrides;

  private LoadClientConfigOptions(
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

    private Builder() {}

    private Builder(LoadClientConfigOptions options) {
      this.configFilePath = options.configFilePath;
      this.configFileData = options.configFileData;
      this.strictConfigFile = options.strictConfigFile;
      this.envOverrides = options.envOverrides;
    }

    /** If true, will error if there are unrecognized keys. Defaults to false. */
    public Builder setStrictConfigFile(boolean configFileStrict) {
      this.strictConfigFile = configFileStrict;
      return this;
    }

    /**
     * Set environment variable overrides. If set, these will be used instead of the actual
     * environment variables. If not set, the actual environment variables will be used.
     */
    public Builder setEnvOverrides(Map<String, String> envOverrides) {
      this.envOverrides = envOverrides;
      return this;
    }

    /**
     * TOML data to load for config. If set, this overrides any file loading. Cannot be set if
     * ConfigFilePath is set. Ignored if DisableFile is true.
     */
    public Builder setConfigFileData(byte[] bytes) {
      this.configFileData = bytes;
      return this;
    }

    /**
     * Override the file path to use to load the TOML file for config. Defaults to
     * TEMPORAL_CONFIG_FILE environment variable or if that is unset/empty, defaults to
     * [os.UserConfigDir]/temporalio/temporal.toml. If ConfigFileData is set, this cannot be set and
     * no file loading from disk occurs. Ignored if DisableFile is true.
     */
    public Builder setConfigFilePath(String configFilePath) {
      this.configFilePath = configFilePath;
      return this;
    }

    public LoadClientConfigOptions build() {
      return new LoadClientConfigOptions(
          configFilePath, configFileData, strictConfigFile, envOverrides);
    }
  }
}
