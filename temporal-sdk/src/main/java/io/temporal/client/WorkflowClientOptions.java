package io.temporal.client;

import io.temporal.api.enums.v1.QueryRejectCondition;
import io.temporal.common.Experimental;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.GlobalDataConverter;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Options for WorkflowClient configuration. */
public final class WorkflowClientOptions {

  private static final WorkflowClientOptions DEFAULT_INSTANCE;
  private static final String DEFAULT_NAMESPACE = "default";
  private static final String DEFAULT_BINARY_CHECKSUM = "";

  static {
    DEFAULT_INSTANCE = newBuilder().build();
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(WorkflowClientOptions options) {
    return new Builder(options);
  }

  public WorkflowClientOptions.Builder toBuilder() {
    return new WorkflowClientOptions.Builder(this);
  }

  public static WorkflowClientOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  public static final class Builder {

    private String namespace;
    private DataConverter dataConverter;
    private WorkflowClientInterceptor[] interceptors;
    private String identity;
    private String binaryChecksum;
    private List<ContextPropagator> contextPropagators;
    private QueryRejectCondition queryRejectCondition;
    private List<Object> plugins;

    private Builder() {}

    private Builder(WorkflowClientOptions options) {
      if (options == null) {
        return;
      }
      namespace = options.namespace;
      dataConverter = options.dataConverter;
      interceptors = options.interceptors;
      identity = options.identity;
      binaryChecksum = options.binaryChecksum;
      contextPropagators = options.contextPropagators;
      queryRejectCondition = options.queryRejectCondition;
      plugins = options.plugins != null ? new ArrayList<>(options.plugins) : null;
    }

    public Builder setNamespace(String namespace) {
      this.namespace = namespace;
      return this;
    }

    /**
     * Overrides a data converter implementation used serialize workflow and activity arguments and
     * results.
     *
     * <p>Default is {@link DataConverter#getDefaultInstance()}.
     */
    public Builder setDataConverter(DataConverter dataConverter) {
      this.dataConverter = Objects.requireNonNull(dataConverter);
      return this;
    }

    /**
     * Interceptor used to intercept workflow client calls.
     *
     * @param interceptors not null
     */
    public Builder setInterceptors(WorkflowClientInterceptor... interceptors) {
      this.interceptors = Objects.requireNonNull(interceptors);
      return this;
    }

    /**
     * Override human readable identity of the worker. Identity is used to identify a worker and is
     * recorded in the workflow history events. For example when a worker gets an activity task the
     * correspondent ActivityTaskStarted event contains the worker identity as a field. Default is
     * whatever <code>(ManagementFactory.getRuntimeMXBean().getName()
     * </code> returns.
     */
    public Builder setIdentity(String identity) {
      this.identity = identity;
      return this;
    }

    /**
     * Sets worker binary checksum, which gets propagated in all history events and can be used for
     * auto-reset assuming that every build has a new unique binary checksum. Can be null.
     *
     * @deprecated use {@link io.temporal.worker.WorkerOptions.Builder#setBuildId(String)} instead.
     */
    @Deprecated
    public Builder setBinaryChecksum(String binaryChecksum) {
      this.binaryChecksum = binaryChecksum;
      return this;
    }

    /**
     * @param contextPropagators specifies the list of context propagators to use with the client.
     */
    public Builder setContextPropagators(List<ContextPropagator> contextPropagators) {
      this.contextPropagators = contextPropagators;
      return this;
    }

    /**
     * Should a query be rejected by closed and failed workflows.
     *
     * <p>Default is {@link QueryRejectCondition#QUERY_REJECT_CONDITION_UNSPECIFIED} which means
     * that closed and failed workflows are still queryable.
     */
    public Builder setQueryRejectCondition(QueryRejectCondition queryRejectCondition) {
      this.queryRejectCondition = queryRejectCondition;
      return this;
    }

    /**
     * Sets the plugins to use with this client. Plugins can modify client and worker configuration,
     * intercept connection, and wrap execution lifecycle.
     *
     * <p>Each plugin should implement {@link io.temporal.client.Plugin} and/or {@link
     * io.temporal.worker.Plugin}. Plugins that implement both interfaces are automatically
     * propagated to workers created from this client.
     *
     * @param plugins the list of plugins to use (each should implement Plugin)
     * @return this builder for chaining
     * @see io.temporal.client.Plugin
     * @see io.temporal.worker.Plugin
     */
    @Experimental
    public Builder setPlugins(List<?> plugins) {
      this.plugins = plugins != null ? new ArrayList<>(plugins) : null;
      return this;
    }

    /**
     * Adds a plugin to use with this client. Plugins can modify client and worker configuration,
     * intercept connection, and wrap execution lifecycle.
     *
     * <p>The plugin should implement {@link io.temporal.client.Plugin} and/or {@link
     * io.temporal.worker.Plugin}. Plugins that implement both interfaces are automatically
     * propagated to workers created from this client.
     *
     * @param plugin the plugin to add (should implement Plugin)
     * @return this builder for chaining
     * @see io.temporal.client.Plugin
     * @see io.temporal.worker.Plugin
     */
    @Experimental
    public Builder addPlugin(Object plugin) {
      if (this.plugins == null) {
        this.plugins = new ArrayList<>();
      }
      this.plugins.add(Objects.requireNonNull(plugin, "Plugin cannot be null"));
      return this;
    }

    public WorkflowClientOptions build() {
      return new WorkflowClientOptions(
          namespace,
          dataConverter,
          interceptors,
          identity,
          binaryChecksum,
          contextPropagators,
          queryRejectCondition,
          plugins);
    }

    public WorkflowClientOptions validateAndBuildWithDefaults() {
      String name = identity == null ? ManagementFactory.getRuntimeMXBean().getName() : identity;
      return new WorkflowClientOptions(
          namespace == null ? DEFAULT_NAMESPACE : namespace,
          dataConverter == null ? GlobalDataConverter.get() : dataConverter,
          interceptors == null ? EMPTY_INTERCEPTOR_ARRAY : interceptors,
          name,
          binaryChecksum == null ? DEFAULT_BINARY_CHECKSUM : binaryChecksum,
          contextPropagators == null ? EMPTY_CONTEXT_PROPAGATORS : contextPropagators,
          queryRejectCondition == null
              ? QueryRejectCondition.QUERY_REJECT_CONDITION_UNSPECIFIED
              : queryRejectCondition,
          plugins == null ? EMPTY_PLUGINS : plugins);
    }
  }

  private static final WorkflowClientInterceptor[] EMPTY_INTERCEPTOR_ARRAY =
      new WorkflowClientInterceptor[0];

  private static final List<ContextPropagator> EMPTY_CONTEXT_PROPAGATORS = Collections.emptyList();

  private static final List<Object> EMPTY_PLUGINS = Collections.emptyList();

  private final String namespace;

  private final DataConverter dataConverter;

  private final WorkflowClientInterceptor[] interceptors;

  private final String identity;

  private final String binaryChecksum;

  private final List<ContextPropagator> contextPropagators;

  private final QueryRejectCondition queryRejectCondition;

  private final List<Object> plugins;

  private WorkflowClientOptions(
      String namespace,
      DataConverter dataConverter,
      WorkflowClientInterceptor[] interceptors,
      String identity,
      String binaryChecksum,
      List<ContextPropagator> contextPropagators,
      QueryRejectCondition queryRejectCondition,
      List<Object> plugins) {
    this.namespace = namespace;
    this.dataConverter = dataConverter;
    this.interceptors = interceptors;
    this.identity = identity;
    this.binaryChecksum = binaryChecksum;
    this.contextPropagators = contextPropagators;
    this.queryRejectCondition = queryRejectCondition;
    this.plugins = plugins;
  }

  /**
   * Should be non-null on a valid instance
   *
   * @return namespace
   */
  public String getNamespace() {
    return namespace;
  }

  public DataConverter getDataConverter() {
    return dataConverter;
  }

  public WorkflowClientInterceptor[] getInterceptors() {
    return interceptors;
  }

  /**
   * Should be non-null on a valid instance
   *
   * @return identity
   */
  public String getIdentity() {
    return identity;
  }

  @Deprecated
  public String getBinaryChecksum() {
    return binaryChecksum;
  }

  /**
   * @return the list of context propagators to use with the client.
   */
  public List<ContextPropagator> getContextPropagators() {
    return contextPropagators;
  }

  public QueryRejectCondition getQueryRejectCondition() {
    return queryRejectCondition;
  }

  /**
   * Returns the list of plugins configured for this client.
   *
   * <p>Each plugin implements {@link io.temporal.client.Plugin} and/or {@link
   * io.temporal.worker.Plugin}. Plugins that implement both interfaces are automatically propagated
   * to workers created from this client.
   *
   * @return an unmodifiable list of plugins, never null
   */
  @Experimental
  public List<?> getPlugins() {
    return plugins != null ? Collections.unmodifiableList(plugins) : Collections.emptyList();
  }

  @Override
  public String toString() {
    return "WorkflowClientOptions{"
        + "namespace='"
        + namespace
        + '\''
        + ", dataConverter="
        + dataConverter
        + ", interceptors="
        + Arrays.toString(interceptors)
        + ", identity='"
        + identity
        + '\''
        + ", binaryChecksum='"
        + binaryChecksum
        + '\''
        + ", contextPropagators="
        + contextPropagators
        + ", queryRejectCondition="
        + queryRejectCondition
        + ", plugins="
        + plugins
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    WorkflowClientOptions that = (WorkflowClientOptions) o;
    return com.google.common.base.Objects.equal(namespace, that.namespace)
        && com.google.common.base.Objects.equal(dataConverter, that.dataConverter)
        && Arrays.equals(interceptors, that.interceptors)
        && com.google.common.base.Objects.equal(identity, that.identity)
        && com.google.common.base.Objects.equal(binaryChecksum, that.binaryChecksum)
        && com.google.common.base.Objects.equal(contextPropagators, that.contextPropagators)
        && queryRejectCondition == that.queryRejectCondition
        && com.google.common.base.Objects.equal(plugins, that.plugins);
  }

  @Override
  public int hashCode() {
    return com.google.common.base.Objects.hashCode(
        namespace,
        dataConverter,
        Arrays.hashCode(interceptors),
        identity,
        binaryChecksum,
        contextPropagators,
        queryRejectCondition,
        plugins);
  }
}
