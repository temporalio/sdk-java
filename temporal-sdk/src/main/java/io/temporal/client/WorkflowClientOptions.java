package io.temporal.client;

import io.temporal.api.enums.v1.QueryRejectCondition;
import io.temporal.common.Experimental;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.GlobalDataConverter;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import java.lang.management.ManagementFactory;
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
    private WorkflowClientPlugin[] plugins;

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
      plugins = options.plugins;
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
     * Sets the workflow client plugins to use with this client. Plugins can modify client
     * configuration.
     *
     * <p>Plugins that also implement {@link io.temporal.worker.WorkerPlugin} are automatically
     * propagated to workers created from this client.
     *
     * @param plugins the workflow client plugins to use
     * @return this builder for chaining
     * @see WorkflowClientPlugin
     * @see io.temporal.worker.WorkerPlugin
     */
    @Experimental
    public Builder setPlugins(WorkflowClientPlugin... plugins) {
      this.plugins = Objects.requireNonNull(plugins);
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
          plugins == null ? EMPTY_PLUGINS : plugins);
    }

    /**
     * Validates options and builds with defaults applied.
     *
     * <p>Note: If plugins are configured via {@link #setPlugins(WorkflowClientPlugin...)}, they
     * will have an opportunity to modify options after this method is called, when the options are
     * passed to {@link WorkflowClient#newInstance}. This means validation performed here occurs
     * before plugin modifications. In most cases, users should simply call {@link #build()} and let
     * the client creation handle validation.
     *
     * @return validated options with defaults applied
     */
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

  private static final WorkflowClientPlugin[] EMPTY_PLUGINS = new WorkflowClientPlugin[0];

  private final String namespace;

  private final DataConverter dataConverter;

  private final WorkflowClientInterceptor[] interceptors;

  private final String identity;

  private final String binaryChecksum;

  private final List<ContextPropagator> contextPropagators;

  private final QueryRejectCondition queryRejectCondition;

  private final WorkflowClientPlugin[] plugins;

  private WorkflowClientOptions(
      String namespace,
      DataConverter dataConverter,
      WorkflowClientInterceptor[] interceptors,
      String identity,
      String binaryChecksum,
      List<ContextPropagator> contextPropagators,
      QueryRejectCondition queryRejectCondition,
      WorkflowClientPlugin[] plugins) {
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
   * Returns the workflow client plugins configured for this client.
   *
   * <p>Plugins that also implement {@link io.temporal.worker.WorkerPlugin} are automatically
   * propagated to workers created from this client.
   *
   * @return the array of workflow client plugins, never null
   */
  @Experimental
  public WorkflowClientPlugin[] getPlugins() {
    return plugins;
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
        + Arrays.toString(plugins)
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
        && Arrays.equals(plugins, that.plugins);
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
        Arrays.hashCode(plugins));
  }
}
