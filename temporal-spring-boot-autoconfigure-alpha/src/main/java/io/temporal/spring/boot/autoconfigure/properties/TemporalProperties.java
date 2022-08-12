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

import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties(prefix = "spring.temporal")
@ConstructorBinding
public class TemporalProperties extends NamespaceProperties {
  private final @NestedConfigurationProperty @Nonnull ConnectionProperties connection;
  private final @NestedConfigurationProperty @Nullable TestServerProperties testServer;
  private final @NestedConfigurationProperty @Nullable ObservabilityProperties observability;
  private final @Nullable Boolean startWorkers;

  public TemporalProperties(
      @Nullable String namespace,
      @Nullable WorkersAutoDiscoveryProperties workersAutoDiscovery,
      @Nullable ClientProperties client,
      @Nullable List<WorkerProperties> workers,
      @Nonnull ConnectionProperties connection,
      @Nullable TestServerProperties testServer,
      @Nullable ObservabilityProperties observability,
      @Nullable Boolean startWorkers) {
    super(namespace, workersAutoDiscovery, client, workers);
    this.connection = connection;
    this.testServer = testServer;
    this.observability = observability;
    this.startWorkers = startWorkers;
  }

  @Nonnull
  public ConnectionProperties getConnection() {
    return connection;
  }

  @Nullable
  public TestServerProperties getTestServer() {
    return testServer;
  }

  @Nullable
  public ObservabilityProperties getObservability() {
    return observability;
  }

  @Nullable
  public Boolean getStartWorkers() {
    return startWorkers;
  }
}
