/*
 *  Copyright (C) 2021 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.serviceclient;

import io.grpc.netty.shaded.io.netty.handler.ssl.ApplicationProtocolConfig;
import io.grpc.netty.shaded.io.netty.handler.ssl.ApplicationProtocolNames;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public class SimpleSslContextBuilder {

  // Default TLS protocol config that is used to communicate with TLS enabled temporal backend.
  private static final ApplicationProtocolConfig DEFAULT_APPLICATION_PROTOCOL_CONFIG =
      new ApplicationProtocolConfig(
          // HTTP/2 over TLS mandates the use of ALPN to negotiate the use of the protocol.
          ApplicationProtocolConfig.Protocol.ALPN,
          // NO_ADVERTISE is the only mode supported by both OpenSsl and JDK providers.
          ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
          // ACCEPT is the only mode supported by both OpenSsl and JDK providers.
          ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
          // gRPC requires http2 protocol.
          ApplicationProtocolNames.HTTP_2);

  private final InputStream keyCertChain;
  private final InputStream key;
  private TrustManager trustManager;
  private boolean useInsecureTrustManager;
  private String keyPassword;

  /**
   * @param keyCertChain - an input stream for an X.509 client certificate chain in PEM format.
   * @param key - an input stream for a PKCS#8 client private key in PEM format.
   */
  public static SimpleSslContextBuilder newBuilder(InputStream keyCertChain, InputStream key) {
    return new SimpleSslContextBuilder(keyCertChain, key);
  }

  private SimpleSslContextBuilder(InputStream keyCertChain, InputStream key) {
    this.keyCertChain = keyCertChain;
    this.key = key;
  }

  /**
   * Builds {@link SslContext} from specified parameters. If trust manager is set then it will be
   * used to verify server authority, otherwise system default trust manager (or if {@link
   * #useInsecureTrustManager} is set then insecure trust manager) is going to be used.
   *
   * @return - {@link SslContext} that can be used with the {@link
   *     WorkflowServiceStubsOptions.Builder#setSslContext(SslContext)}
   * @throws SSLException - when it was unable to build the context.
   */
  public SslContext build() throws SSLException {
    if (trustManager != null && useInsecureTrustManager)
      throw new IllegalArgumentException(
          "Can not use insecure trust manager if custom trust manager is set.");
    return SslContextBuilder.forClient()
        .trustManager(
            trustManager != null
                ? trustManager
                : useInsecureTrustManager
                    ? InsecureTrustManagerFactory.INSTANCE.getTrustManagers()[0]
                    : getDefaultTrustManager())
        .keyManager(keyCertChain, key, keyPassword)
        .applicationProtocolConfig(DEFAULT_APPLICATION_PROTOCOL_CONFIG)
        .build();
  }

  /**
   * @param trustManager - custom trust manager that should be used with the SSLContext for
   *     verifying server CA authority.
   */
  public void setTrustManager(TrustManager trustManager) {
    this.trustManager = trustManager;
  }

  /**
   * @param useInsecureTrustManager - if set to true then insecure trust manager is going to be used
   *     instead of the system default one. Note that this makes client vulnerable to man in the
   *     middle attack. Use with caution.
   */
  public void setUseInsecureTrustManager(boolean useInsecureTrustManager) {
    this.useInsecureTrustManager = useInsecureTrustManager;
  }

  /** @param keyPassword - the password of the key, or null if it's not password-protected. */
  public void setKeyPassword(String keyPassword) {
    this.keyPassword = keyPassword;
  }

  /**
   * @return system default trust manager.
   * @throws UnknownDefaultTrustManagerException, which can be caused by {@link
   *     NoSuchAlgorithmException} if {@link TrustManagerFactory#getInstance(String)} doesn't
   *     support default algorithm, {@link KeyStoreException} in case if {@link KeyStore}
   *     initialization failed or if no {@link X509TrustManager} has been found.
   */
  private X509TrustManager getDefaultTrustManager() {
    TrustManagerFactory tmf;
    try {
      tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      // Using null here initialises the TMF with the default trust store.
      tmf.init((KeyStore) null);
    } catch (KeyStoreException | NoSuchAlgorithmException e) {
      throw new UnknownDefaultTrustManagerException(e);
    }

    for (TrustManager tm : tmf.getTrustManagers()) {
      if (tm instanceof X509TrustManager) {
        return (X509TrustManager) tm;
      }
    }
    throw new UnknownDefaultTrustManagerException(
        "Unable to find X509TrustManager in the list of default trust managers.");
  }

  /**
   * Exception that is thrown in case if builder was unable to derive default system trust manager.
   */
  public static final class UnknownDefaultTrustManagerException extends RuntimeException {
    public UnknownDefaultTrustManagerException(Throwable cause) {
      super(cause);
    }

    public UnknownDefaultTrustManagerException(String message) {
      super(message);
    }
  }
}
