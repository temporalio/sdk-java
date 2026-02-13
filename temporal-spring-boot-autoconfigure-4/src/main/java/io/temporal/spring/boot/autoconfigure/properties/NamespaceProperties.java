package io.temporal.spring.boot.autoconfigure.properties;

import com.google.common.base.MoreObjects;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

public class NamespaceProperties {
  public static final String NAMESPACE_DEFAULT = "default";

  private final @NestedConfigurationProperty @Nullable WorkersAutoDiscoveryProperties
      workersAutoDiscovery;
  private final @Nullable List<WorkerProperties> workers;
  private final @Nonnull String namespace;
  private final @Nullable WorkflowCacheProperties workflowCache;
  private final @Nonnull Boolean ignoreDuplicateDefinitions;

  public NamespaceProperties(
      @Nullable String namespace,
      @Nullable WorkersAutoDiscoveryProperties workersAutoDiscovery,
      @Nullable List<WorkerProperties> workers,
      @Nullable WorkflowCacheProperties workflowCache,
      @Nullable Boolean ignoreDuplicateDefinitions) {
    this.workersAutoDiscovery = workersAutoDiscovery;
    this.workers = workers;
    this.namespace = MoreObjects.firstNonNull(namespace, NAMESPACE_DEFAULT);
    this.workflowCache = workflowCache;
    this.ignoreDuplicateDefinitions =
        MoreObjects.firstNonNull(ignoreDuplicateDefinitions, Boolean.FALSE);
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

  @Nonnull
  public Boolean isIgnoreDuplicateDefinitions() {
    return ignoreDuplicateDefinitions;
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
