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

import com.google.common.base.MoreObjects;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.springframework.boot.context.properties.ConstructorBinding;

public class NonRootNamespaceProperties extends NamespaceProperties {

  public static final String NAMESPACE_NON_ROOT = "non-root-" + UUID.randomUUID();
  private final @Nonnull String alias;

  /** Whether to auto-register the namespace. The default is false. */
  private final @Nullable Boolean namespaceAutoRegister;

  /** The retention period for workflow history. The default is 3 days. */
  private final @Nullable Duration retentionPeriod;

  /** The state of the history archival. The default is false means disable. */
  private final @Nullable Boolean historyArchivalState;

  /** The state of the visibility archival. The default is false means disable. */
  private final @Nullable Boolean visibilityArchivalState;

  @ConstructorBinding
  public NonRootNamespaceProperties(
      @Nonnull String alias,
      @Nullable String namespace,
      @Nullable Boolean namespaceAutoRegister,
      @Nullable Boolean historyArchivalState,
      @Nullable Boolean visibilityArchivalState,
      @Nullable Duration retentionPeriod,
      @Nullable WorkersAutoDiscoveryProperties workersAutoDiscovery,
      @Nullable List<WorkerProperties> workers,
      @Nullable WorkflowCacheProperties workflowCache) {
    super(
        MoreObjects.firstNonNull(namespace, NAMESPACE_NON_ROOT),
        workersAutoDiscovery,
        workers,
        workflowCache);
    this.alias = alias;
    this.namespaceAutoRegister = namespaceAutoRegister;
    this.retentionPeriod = retentionPeriod;
    this.historyArchivalState = historyArchivalState;
    this.visibilityArchivalState = visibilityArchivalState;
  }

  @Nonnull
  public String getAlias() {
    return alias;
  }

  @Nullable
  public Boolean getNamespaceAutoRegister() {
    return namespaceAutoRegister;
  }

  @Nullable
  public Duration getRetentionPeriod() {
    return retentionPeriod;
  }

  @Nullable
  public Boolean getHistoryArchivalState() {
    return historyArchivalState;
  }

  @Nullable
  public Boolean getVisibilityArchivalState() {
    return visibilityArchivalState;
  }
}
