package io.temporal.envconfig;

import io.temporal.common.Experimental;

/** TLS configuration for a client. */
@Experimental
public class ClientConfigTLS {
  /** Create a builder for {@link ClientConfigTLS}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** Create a builder from an existing {@link ClientConfigTLS}. */
  public static Builder newBuilder(ClientConfigTLS config) {
    return new Builder(config);
  }

  /** Returns a default instance of {@link ClientConfigTLS} with all fields unset. */
  public static ClientConfigTLS getDefaultInstance() {
    return new Builder().build();
  }

  private final Boolean disabled;
  private final String clientCertPath;
  private final byte[] clientCertData;
  private final String clientKeyPath;
  private final byte[] clientKeyData;
  private final String serverCACertPath;
  private final byte[] serverCACertData;
  private final String serverName;
  private final Boolean disableHostVerification;

  private ClientConfigTLS(
      Boolean disabled,
      String clientCertPath,
      byte[] clientCertData,
      String clientKeyPath,
      byte[] clientKeyData,
      String serverCACertPath,
      byte[] serverCACertData,
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

  public Boolean isDisabled() {
    return disabled;
  }

  public String getClientCertPath() {
    return clientCertPath;
  }

  public byte[] getClientCertData() {
    return clientCertData;
  }

  public String getClientKeyPath() {
    return clientKeyPath;
  }

  public byte[] getClientKeyData() {
    return clientKeyData;
  }

  public String getServerCACertPath() {
    return serverCACertPath;
  }

  public byte[] getServerCACertData() {
    return serverCACertData;
  }

  public String getServerName() {
    return serverName;
  }

  public Boolean isDisableHostVerification() {
    return disableHostVerification;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  public static class Builder {
    private String clientCertPath;
    private byte[] clientCertData;
    private String clientKeyPath;
    private byte[] clientKeyData;
    private String serverCACertPath;
    private byte[] serverCACertData;
    private Boolean disabled;
    private String serverName;
    private Boolean disableHostVerification;

    private Builder() {}

    private Builder(ClientConfigTLS clientConfigTLS) {
      this.disabled = clientConfigTLS.disabled;
      this.serverName = clientConfigTLS.serverName;
      this.clientCertPath = clientConfigTLS.clientCertPath;
      this.clientCertData = clientConfigTLS.clientCertData;
      this.clientKeyPath = clientConfigTLS.clientKeyPath;
      this.clientKeyData = clientConfigTLS.clientKeyData;
      this.serverCACertPath = clientConfigTLS.serverCACertPath;
      this.serverCACertData = clientConfigTLS.serverCACertData;
      this.disableHostVerification = clientConfigTLS.disableHostVerification;
    }

    /** Disable TLS. Default: false. */
    public Builder setDisabled(Boolean disabled) {
      this.disabled = disabled;
      return this;
    }

    /**
     * Server name for TLS verification. If not set, the hostname from the target endpoint will be
     * used.
     */
    public Builder setServerName(String serverName) {
      this.serverName = serverName;
      return this;
    }

    /** Path to client mTLS certificate. Mutually exclusive with ClientCertData. */
    public Builder setClientCertPath(String clientCertPath) {
      this.clientCertPath = clientCertPath;
      return this;
    }

    /** PEM bytes for client mTLS certificate. Mutually exclusive with ClientCertPath. */
    public Builder setClientCertData(byte[] bytes) {
      this.clientCertData = bytes;
      return this;
    }

    /** Path to client mTLS key. Mutually exclusive with ClientKeyData. */
    public Builder setClientKeyPath(String clientKeyPath) {
      this.clientKeyPath = clientKeyPath;
      return this;
    }

    /** PEM bytes for client mTLS key. Mutually exclusive with ClientKeyPath. */
    public Builder setClientKeyData(byte[] clientKeyData) {
      this.clientKeyData = clientKeyData;
      return this;
    }

    /** Path to server CA cert override. Mutually exclusive with ServerCACertData. */
    public Builder setServerCACertPath(String serverCACertPath) {
      this.serverCACertPath = serverCACertPath;
      return this;
    }

    /** PEM bytes for server CA cert override. Mutually exclusive with ServerCACertPath. */
    public Builder setServerCACertData(byte[] serverCACertData) {
      this.serverCACertData = serverCACertData;
      return this;
    }

    /** Disable server host verification. Default: false */
    public Builder setDisableHostVerification(Boolean disableHostVerification) {
      this.disableHostVerification = disableHostVerification;
      return this;
    }

    public ClientConfigTLS build() {
      return new ClientConfigTLS(
          disabled,
          clientCertPath,
          clientCertData,
          clientKeyPath,
          clientKeyData,
          serverCACertPath,
          serverCACertData,
          serverName,
          disableHostVerification);
    }
  }
}
