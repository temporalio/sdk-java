package io.temporal.spring.boot.autoconfigure.properties;

import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties(prefix = "spring.temporal")
public class TemporalProperties extends NamespaceProperties {

  private final @NestedConfigurationProperty @Nonnull ConnectionProperties connection;
  private final @NestedConfigurationProperty @Nullable TestServerProperties testServer;
  private final @Nullable Boolean startWorkers;
  private final @Nullable List<NonRootNamespaceProperties> namespaces;

  public TemporalProperties(
      @Nullable String namespace,
      @Nullable List<NonRootNamespaceProperties> namespaces,
      @Nullable WorkersAutoDiscoveryProperties workersAutoDiscovery,
      @Nullable List<WorkerProperties> workers,
      @Nullable WorkflowCacheProperties workflowCache,
      @Nonnull ConnectionProperties connection,
      @Nullable TestServerProperties testServer,
      @Nullable Boolean startWorkers,
      @Nullable Boolean ignoreDuplicateDefinitions) {
    super(namespace, workersAutoDiscovery, workers, workflowCache, ignoreDuplicateDefinitions);
    this.connection = connection;
    this.testServer = testServer;
    this.startWorkers = startWorkers;
    this.namespaces = namespaces;
  }

  public List<NonRootNamespaceProperties> getNamespaces() {
    return namespaces;
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
  public Boolean getStartWorkers() {
    return startWorkers;
  }
}
