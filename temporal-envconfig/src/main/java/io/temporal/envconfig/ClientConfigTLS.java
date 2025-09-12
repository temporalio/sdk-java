package io.temporal.envconfig;

import io.temporal.common.Experimental;

//// Path to client mTLS certificate. Mutually exclusive with ClientCertData.
// ClientCertPath string
//        // PEM bytes for client mTLS certificate. Mutually exclusive with ClientCertPath.
//        ClientCertData []byte
//        // Path to client mTLS key. Mutually exclusive with ClientKeyData.
//        ClientKeyPath string
//        // PEM bytes for client mTLS key. Mutually exclusive with ClientKeyPath.
//        ClientKeyData []byte
//        // Path to server CA cert override. Mutually exclusive with ServerCACertData.
//        ServerCACertPath string
//        // PEM bytes for server CA cert override. Mutually exclusive with ServerCACertPath.
//        ServerCACertData []byte

@Experimental
public class ClientConfigTLS {
  public static Builder newBuilder() {
    return new Builder();
  }

  private final boolean disabled;
  private final String clientCertPath;
  private final byte[] clientCertData;
  private final String clientKeyPath;
  private final byte[] clientKeyData;
  private final String serverCACertPath;
  private final byte[] serverCACertData;
  private final String serverName;

  public ClientConfigTLS(
      boolean disabled,
      String clientCertPath,
      byte[] clientCertData,
      String clientKeyPath,
      byte[] clientKeyData,
      String serverCACertPath,
      byte[] serverCACertData,
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
  }

  public boolean isDisabled() {
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

  public Builder toBuilder() {
    return new Builder(this);
  }

  public static class Builder {
    private boolean disabled;
    private String serverName;

    public Builder() {}

    public Builder(ClientConfigTLS clientConfigTLS) {
      this.disabled = clientConfigTLS.disabled;
      this.serverName = clientConfigTLS.serverName;
    }

    public Builder setDisabled(boolean disabled) {
      this.disabled = disabled;
      return this;
    }

    public Builder setServerName(String serverName) {
      this.serverName = serverName;
      return this;
    }

    public Builder setClientCertPath(String clientCertPath) {
      return this;
    }

    public Builder setClientCertData(byte[] bytes) {
      return this;
    }

    public Builder setClientKeyPath(String clientKeyPath) {
      return this;
    }

    public Builder setClientKeyData(byte[] bytes) {
      return this;
    }

    public Builder setServerCACertPath(String s) {
      return this;
    }

    public Builder setServerCACertData(byte[] bytes) {
      return this;
    }

    public ClientConfigTLS build() {
      return new ClientConfigTLS(disabled, null, null, null, null, null, null, serverName, false);
    }
  }
}
