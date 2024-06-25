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

package io.temporal.spring.boot.autoconfigure.template;

import com.google.common.base.Preconditions;
import com.uber.m3.tally.Scope;
import io.temporal.internal.common.ShadingHelpers;
import io.temporal.serviceclient.SimpleSslContextBuilder;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.properties.ConnectionProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.support.BeanDefinitionValidationException;
import org.springframework.util.ResourceUtils;

public class ServiceStubOptionsTemplate {
  private final @Nonnull ConnectionProperties connectionProperties;
  private final @Nullable Scope metricsScope;
  private final @Nullable TemporalOptionsCustomizer<WorkflowServiceStubsOptions.Builder>
      workflowServiceStubsCustomizer;

  public ServiceStubOptionsTemplate(
      @Nonnull ConnectionProperties connectionProperties,
      @Nullable Scope metricsScope,
      @Nullable
          TemporalOptionsCustomizer<WorkflowServiceStubsOptions.Builder>
              workflowServiceStubsCustomizer) {
    this.connectionProperties = connectionProperties;
    this.metricsScope = metricsScope;
    this.workflowServiceStubsCustomizer = workflowServiceStubsCustomizer;
  }

  public WorkflowServiceStubsOptions createServiceStubOptions() {
    WorkflowServiceStubsOptions.Builder stubsOptionsBuilder =
        WorkflowServiceStubsOptions.newBuilder();

    Preconditions.checkNotNull(connectionProperties.getTarget(), "target");
    stubsOptionsBuilder.setTarget(connectionProperties.getTarget());

    stubsOptionsBuilder.setEnableHttps(Boolean.TRUE.equals(connectionProperties.isEnableHttps()));

    configureMTLS(connectionProperties.getMTLS(), stubsOptionsBuilder);

    if (metricsScope != null) {
      stubsOptionsBuilder.setMetricsScope(metricsScope);
    }

    if (workflowServiceStubsCustomizer != null) {
      stubsOptionsBuilder = workflowServiceStubsCustomizer.customize(stubsOptionsBuilder);
    }

    return stubsOptionsBuilder.build();
  }

  private void configureMTLS(
      @Nullable ConnectionProperties.MTLSProperties mtlsProperties,
      WorkflowServiceStubsOptions.Builder stubsOptionsBuilder) {
    if (mtlsProperties == null
        || (mtlsProperties.getKeyFile() == null
            && mtlsProperties.getCertChainFile() == null
            && mtlsProperties.getKey() == null
            && mtlsProperties.getCertChain() == null)) {
      return;
    }

    Integer pkcs = mtlsProperties.getPKCS();
    if (pkcs != null && pkcs != 8 && pkcs != 12) {
      throw new BeanDefinitionValidationException("Invalid PKCS: " + pkcs);
    }

    String keyFile = mtlsProperties.getKeyFile();
    String key = mtlsProperties.getKey();
    if (keyFile == null && key == null) {
      throw new BeanDefinitionValidationException("key-file or key has to be specified");
    } else if (keyFile != null && key != null) {
      throw new BeanDefinitionValidationException(
          "Both key-file and key can't be specified at the same time");
    }

    String certChainFile = mtlsProperties.getCertChainFile();
    String certChain = mtlsProperties.getCertChain();

    if (pkcs == null) {
      pkcs = certChainFile != null || certChain != null ? 8 : 12;
    }

    if (pkcs == 8) {
      if (certChainFile == null && certChain == null) {
        throw new BeanDefinitionValidationException(
            "cert-chain-file or cert-chain has to be specified for PKCS8");
      }
      if (certChainFile != null && certChain != null) {
        throw new BeanDefinitionValidationException(
            "Both cert-chain-file or cert-chain can't be set at the same time");
      }
      try (InputStream certInputStream =
              certChainFile != null
                  ? Files.newInputStream(ResourceUtils.getFile(certChainFile).toPath())
                  : new ByteArrayInputStream(certChain.getBytes(StandardCharsets.UTF_8));
          InputStream keyInputStream =
              keyFile != null
                  ? Files.newInputStream(ResourceUtils.getFile(keyFile).toPath())
                  : new ByteArrayInputStream(key.getBytes(StandardCharsets.UTF_8)); ) {
        SimpleSslContextBuilder sslBuilder =
            SimpleSslContextBuilder.forPKCS8(certInputStream, keyInputStream);
        applyMTLSProperties(mtlsProperties, sslBuilder);
        ShadingHelpers.buildSslContextAndPublishIntoStubOptions(sslBuilder, stubsOptionsBuilder);
      } catch (IOException e) {
        throw new BeanCreationException("Failure reading PKCS8 mTLS key or cert chain file", e);
      }
    } else {
      if (certChainFile != null || certChain != null) {
        throw new BeanDefinitionValidationException(
            "cert-chain-file or cert-chain can't be specified for PKCS12, cert chain is bundled into the key file");
      }
      if (key != null) {
        throw new BeanDefinitionValidationException(
            "key can't be specified for PKCS12, use key-file");
      }
      try (InputStream keyInputStream =
          Files.newInputStream(ResourceUtils.getFile(keyFile).toPath())) {
        SimpleSslContextBuilder sslBuilder = SimpleSslContextBuilder.forPKCS12(keyInputStream);
        applyMTLSProperties(mtlsProperties, sslBuilder);
        ShadingHelpers.buildSslContextAndPublishIntoStubOptions(sslBuilder, stubsOptionsBuilder);
      } catch (IOException e) {
        throw new BeanCreationException("Failure reading PKCS12 mTLS cert key file", e);
      }
    }

    String serverName = mtlsProperties.getServerName();
    if (serverName != null) {
      stubsOptionsBuilder.setChannelInitializer(
          channelBuilder -> channelBuilder.overrideAuthority(serverName));
    }
  }

  private void applyMTLSProperties(
      ConnectionProperties.MTLSProperties mtlsProperties, SimpleSslContextBuilder sslBuilder) {
    if (mtlsProperties.getKeyPassword() != null) {
      sslBuilder.setKeyPassword(mtlsProperties.getKeyPassword());
    }

    if (Boolean.TRUE.equals(mtlsProperties.getInsecureTrustManager())) {
      sslBuilder.setUseInsecureTrustManager(true);
    }
  }
}
