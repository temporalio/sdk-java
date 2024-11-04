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
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.springframework.boot.context.properties.ConstructorBinding;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

public class NamespaceProperties {
  public static final String NAMESPACE_DEFAULT = "default";

  private final @NestedConfigurationProperty @Nullable WorkersAutoDiscoveryProperties
      workersAutoDiscovery;
  private final @Nullable List<WorkerProperties> workers;
  private final @Nonnull String namespace;
  private final @Nullable WorkflowCacheProperties workflowCache;

  @ConstructorBinding
  public NamespaceProperties(
      @Nullable String namespace,
      @Nullable WorkersAutoDiscoveryProperties workersAutoDiscovery,
      @Nullable List<WorkerProperties> workers,
      @Nullable WorkflowCacheProperties workflowCache) {
    this.workersAutoDiscovery = workersAutoDiscovery;
    this.workers = workers;
    this.namespace = MoreObjects.firstNonNull(namespace, NAMESPACE_DEFAULT);
    this.workflowCache = workflowCache;
  }

  @Nullable
  public WorkersAutoDiscoveryProperties getWorkersAutoDiscovery() {
    return workersAutoDiscovery;
  }

  @Nullable
  public List<WorkerProperties> getWorkers() {
    return workers;
  }

  /**
   * @see io.temporal.client.WorkflowClientOptions.Builder#setNamespace(String)
   */
  @Nonnull
  public String getNamespace() {
    return namespace;
  }

  @Nullable
  public WorkflowCacheProperties getWorkflowCache() {
    return workflowCache;
  }

  public static class WorkflowCacheProperties {
    private final @Nullable Integer maxInstances;
    private final @Nullable Integer maxThreads;
    private final @Nullable Boolean usingVirtualWorkflowThreads;

    /**
     * @param maxInstances max number of workflow instances in the cache. Defines {@link
     *     io.temporal.worker.WorkerFactoryOptions.Builder#setWorkflowCacheSize(int)}
     * @param maxThreads max number of workflow threads in the cache. Defines {@link
     *     io.temporal.worker.WorkerFactoryOptions.Builder#setMaxWorkflowThreadCount(int)}
     * @param usingVirtualWorkflowThreads whether to enable virtual workflow threads. Defines {@link
     *     io.temporal.worker.WorkerFactoryOptions.Builder#setUsingVirtualWorkflowThreads(boolean)}
     */
    @ConstructorBinding
    public WorkflowCacheProperties(
        @Nullable Integer maxInstances,
        @Nullable Integer maxThreads,
        @Nullable Boolean usingVirtualWorkflowThreads) {
      this.maxInstances = maxInstances;
      this.maxThreads = maxThreads;
      this.usingVirtualWorkflowThreads = usingVirtualWorkflowThreads;
    }

    @Nullable
    public Integer getMaxInstances() {
      return maxInstances;
    }

    @Nullable
    public Integer getMaxThreads() {
      return maxThreads;
    }

    public @Nullable Boolean isUsingVirtualWorkflowThreads() {
      return usingVirtualWorkflowThreads;
    }
  }
}
