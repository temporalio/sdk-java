/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.spring.boot.autoconfigure.properties;

import io.temporal.serviceclient.SimpleSslContextBuilder;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.springframework.boot.context.properties.ConstructorBinding;

/** These properties are significantly mirroring {@link WorkflowServiceStubsOptions} */
public class ConnectionProperties {
  public static final String TARGET_LOCAL_SERVICE = "local";

  private final @Nonnull String target;

  private final @Nullable Boolean enableHttps;

  private final @Nullable MTLSProperties mtls;

  private final @Nullable String apiKey;

  /**
   * @param target {@link WorkflowServiceStubsOptions.Builder#setTarget(String)} also support
   *     "local" alias for local temporal setup
   * @param enableHttps {@link WorkflowServiceStubsOptions.Builder#setEnableHttps(boolean)}
   *     (String)}
   */
  @ConstructorBinding
  public ConnectionProperties(
      @Nonnull String target,
      @Nullable Boolean enableHttps,
      @Nullable MTLSProperties mtls,
      @Nullable String apiKey) {
    this.target = target;
    this.enableHttps = Boolean.TRUE.equals(enableHttps);
    this.mtls = mtls;
    this.apiKey = apiKey;
  }

  @Nonnull
  public String getTarget() {
    return target;
  }

  @Nullable
  public Boolean isEnableHttps() {
    return enableHttps;
  }

  @Nullable
  public MTLSProperties getMTLS() {
    return mtls;
  }

  @Nullable
  public String getApiKey() {
    return apiKey;
  }

  public static class MTLSProperties {
    private final @Nullable Integer pkcs;

    private final @Nullable String key;
    private final @Nullable String certChain;
    private final @Nullable String keyFile;
    private final @Nullable String certChainFile;
    private final @Nullable String keyPassword;
    private final @Nullable Boolean insecureTrustManager;
    private final @Nullable String serverName;

    /**
     * @param pkcs number of PKCS standard to use (8 and 12 are supported). Selects if {@link
     *     SimpleSslContextBuilder#forPKCS8} or {@link SimpleSslContextBuilder#forPKCS12} is used.
     *     By default, PKCS 8 is used if certFile is supplied, PKCS 12 is used if not.
     * @param key allows to pass PKCS8 key in PEM format as a string
     * @param certChain allows to pass PKCS8 certificates chain in PEM format as a string
     * @param keyFile path to key file in PEM format for PKCS8 (usually .pem or .key) PFX for PKCS12
     *     (usually .p12 or .pfx)
     * @param certChainFile path to certificates chain file in PEM format for PKCS8
     * @param keyPassword password of the key, or null if it's not password-protected
     * @param insecureTrustManager see {@link
     *     SimpleSslContextBuilder#setUseInsecureTrustManager(boolean)}
     */
    @ConstructorBinding
    public MTLSProperties(
        @Nullable Integer pkcs,
        @Nullable String key,
        @Nullable String certChain,
        @Nullable String keyFile,
        @Nullable String certChainFile,
        @Nullable String keyPassword,
        @Nullable Boolean insecureTrustManager,
        @Nullable String serverName) {
      this.pkcs = pkcs;
      this.key = key;
      this.certChain = certChain;
      this.keyFile = keyFile;
      this.certChainFile = certChainFile;
      this.keyPassword = keyPassword;
      this.insecureTrustManager = insecureTrustManager;
      this.serverName = serverName;
    }

    @Nullable
    public Integer getPKCS() {
      return pkcs;
    }

    @Nullable
    public String getKey() {
      return key;
    }

    @Nullable
    public String getCertChain() {
      return certChain;
    }

    @Nullable
    public String getKeyFile() {
      return keyFile;
    }

    @Nullable
    public String getCertChainFile() {
      return certChainFile;
    }

    @Nullable
    public String getKeyPassword() {
      return keyPassword;
    }

    @Nullable
    public Boolean getInsecureTrustManager() {
      return insecureTrustManager;
    }

    @Nullable
    public String getServerName() {
      return serverName;
    }
  }
}
