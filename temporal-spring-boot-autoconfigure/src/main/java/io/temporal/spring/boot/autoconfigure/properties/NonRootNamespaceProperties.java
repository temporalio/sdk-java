package io.temporal.spring.boot.autoconfigure.properties;

import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.springframework.boot.context.properties.ConstructorBinding;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

public class NonRootNamespaceProperties extends NamespaceProperties {

  /**
   * The bean register name prefix. <br>
   * NOTE: Currently we register a series beans with the same alias. if user set alias, will use it.
   * otherwise use namespace as prefix. - NamespaceTemplate <br>
   * - ClientTemplate <br>
   * - WorkersTemplate <br>
   * - WorkflowClient <br>
   * - ScheduleClient <br>
   * - WorkerFactory <br>
   * You guys can use this alias to get the beans. <br>
   * for example if you set spring.temporal.namespace[0].alias=foo <br>
   * We can get bean via @Autowired @Qualifier("fooNamespaceTemplate") NamespaceTemplate
   */
  private final @Nullable String alias;

  /**
   * Indicate start workers when application start in the namespace. if not set, will use the root
   * namespace startWorkers.
   */
  private final @Nullable Boolean startWorkers;

  /**
   * Connection properties for the namespace. if not set, will use the root namespace connection
   * properties.
   */
  private final @NestedConfigurationProperty @Nullable ConnectionProperties connection;

  @ConstructorBinding
  public NonRootNamespaceProperties(
      @Nullable String alias,
      @Nonnull String namespace,
      @Nullable WorkersAutoDiscoveryProperties workersAutoDiscovery,
      @Nullable List<WorkerProperties> workers,
      @Nullable WorkflowCacheProperties workflowCache,
      @Nullable ConnectionProperties connection,
      @Nullable Boolean startWorkers,
      @Nullable Boolean ignoreDuplicateDefinitions) {
    super(namespace, workersAutoDiscovery, workers, workflowCache, ignoreDuplicateDefinitions);
    this.alias = alias;
    this.connection = connection;
    this.startWorkers = startWorkers;
  }

  @Nullable
  public String getAlias() {
    return alias;
  }

  @Nullable
  public ConnectionProperties getConnection() {
    return connection;
  }

  @Nullable
  public Boolean getStartWorkers() {
    return startWorkers;
  }
}
