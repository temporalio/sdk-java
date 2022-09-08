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

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.StatsReporter;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.common.reporter.MicrometerClientStatsReporter;
import io.temporal.serviceclient.SimpleSslContextBuilder;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.spring.boot.autoconfigure.properties.ConnectionProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.support.BeanDefinitionValidationException;
import org.springframework.util.ResourceUtils;

public class ServiceStubsTemplate {
  private final @Nonnull ConnectionProperties connectionProperties;

  private final @Nullable MeterRegistry meterRegistry;

  // if not null, we work with an environment with defined test server
  private final @Nullable TestWorkflowEnvironmentAdapter testWorkflowEnvironment;

  private WorkflowServiceStubs workflowServiceStubs;

  public ServiceStubsTemplate(
      @Nonnull ConnectionProperties connectionProperties,
      @Nullable MeterRegistry meterRegistry,
      @Nullable TestWorkflowEnvironmentAdapter testWorkflowEnvironment) {
    this.connectionProperties = connectionProperties;
    this.meterRegistry = meterRegistry;
    this.testWorkflowEnvironment = testWorkflowEnvironment;
  }

  public WorkflowServiceStubs getWorkflowServiceStubs() {
    if (workflowServiceStubs == null) {
      this.workflowServiceStubs = createServiceStubs();
    }
    return workflowServiceStubs;
  }

  private WorkflowServiceStubs createServiceStubs() {
    WorkflowServiceStubs workflowServiceStubs;
    if (testWorkflowEnvironment != null) {
      workflowServiceStubs = testWorkflowEnvironment.getWorkflowClient().getWorkflowServiceStubs();
    } else {
      switch (connectionProperties.getTarget().toLowerCase()) {
        case ConnectionProperties.TARGET_LOCAL_SERVICE:
          workflowServiceStubs = WorkflowServiceStubs.newLocalServiceStubs();
          break;
        default:
          WorkflowServiceStubsOptions.Builder stubsOptionsBuilder =
              WorkflowServiceStubsOptions.newBuilder();

          if (connectionProperties.getTarget() != null) {
            stubsOptionsBuilder.setTarget(connectionProperties.getTarget());
          }

          stubsOptionsBuilder.setEnableHttps(
              Boolean.TRUE.equals(connectionProperties.isEnableHttps()));

          configureMTLS(connectionProperties.getMTLS(), stubsOptionsBuilder);

          if (meterRegistry != null) {
            stubsOptionsBuilder.setMetricsScope(createScope(meterRegistry));
          }

          workflowServiceStubs =
              WorkflowServiceStubs.newServiceStubs(
                  stubsOptionsBuilder.validateAndBuildWithDefaults());
      }
    }

    return workflowServiceStubs;
  }

  private Scope createScope(@Nonnull MeterRegistry registry) {
    StatsReporter reporter = new MicrometerClientStatsReporter(registry);
    return new RootScopeBuilder()
        .reporter(reporter)
        .reportEvery(com.uber.m3.util.Duration.ofSeconds(10));
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

    SimpleSslContextBuilder sslBuilder;
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
        sslBuilder = SimpleSslContextBuilder.forPKCS8(certInputStream, keyInputStream);
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
        sslBuilder = SimpleSslContextBuilder.forPKCS12(keyInputStream);
      } catch (IOException e) {
        throw new BeanCreationException("Failure reading PKCS12 mTLS cert key file", e);
      }
    }

    if (mtlsProperties.getKeyPassword() != null) {
      sslBuilder.setKeyPassword(mtlsProperties.getKeyPassword());
    }

    if (Boolean.TRUE.equals(mtlsProperties.getInsecureTrustManager())) {
      sslBuilder.setUseInsecureTrustManager(true);
    }

    try {
      stubsOptionsBuilder.setSslContext(sslBuilder.build());
    } catch (SSLException e) {
      throw new BeanCreationException("Failure building SSLContext", e);
    }
  }
}
