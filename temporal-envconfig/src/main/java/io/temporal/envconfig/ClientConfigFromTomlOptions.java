package io.temporal.envconfig;

/**
 * Options for parsing a client config toml file in {@link ClientConfig#fromToml(byte[],
 * ClientConfigFromTomlOptions)}
 */
public class ClientConfigFromTomlOptions {
  /** Create a builder for {@link ClientConfigFromTomlOptions}. */
  public static Builder newBuilder() {
    return new ClientConfigFromTomlOptions.Builder();
  }

  /** Create a builder from an existing {@link ClientConfigFromTomlOptions}. */
  public static Builder newBuilder(ClientConfigFromTomlOptions options) {
    return new Builder(options);
  }

  /** Returns a default instance of {@link ClientConfigFromTomlOptions} with all fields unset. */
  public static ClientConfigFromTomlOptions getDefaultInstance() {
    return new ClientConfigFromTomlOptions.Builder().build();
  }

  private final Boolean strictConfigFile;

  private ClientConfigFromTomlOptions(Boolean strictConfigFile) {
    this.strictConfigFile = strictConfigFile;
  }

  public Boolean isStrictConfigFile() {
    return strictConfigFile;
  }

  public static class Builder {
    private Boolean strictConfigFile = false;

    private Builder() {}

    private Builder(ClientConfigFromTomlOptions options) {
      this.strictConfigFile = options.strictConfigFile;
    }

    /**
     * When true, the parser will fail if the config file contains unknown fields. Default is false.
     */
    public Builder setStrictConfigFile(Boolean strictConfigFile) {
      this.strictConfigFile = strictConfigFile;
      return this;
    }

    public ClientConfigFromTomlOptions build() {
      return new ClientConfigFromTomlOptions(strictConfigFile);
    }
  }
}
