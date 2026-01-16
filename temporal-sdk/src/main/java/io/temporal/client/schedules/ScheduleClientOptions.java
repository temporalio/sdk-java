package io.temporal.client.schedules;

import io.temporal.common.Experimental;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.GlobalDataConverter;
import io.temporal.common.interceptors.ScheduleClientInterceptor;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Options for ScheduleClient configuration. */
public final class ScheduleClientOptions {

  public static ScheduleClientOptions.Builder newBuilder() {
    return new ScheduleClientOptions.Builder();
  }

  public static ScheduleClientOptions.Builder newBuilder(ScheduleClientOptions options) {
    return new ScheduleClientOptions.Builder(options);
  }

  public static ScheduleClientOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  public ScheduleClientOptions.Builder toBuilder() {
    return new ScheduleClientOptions.Builder(this);
  }

  private static final ScheduleClientOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = ScheduleClientOptions.newBuilder().build();
  }

  public static final class Builder {
    private static final String DEFAULT_NAMESPACE = "default";
    private static final List<ContextPropagator> EMPTY_CONTEXT_PROPAGATORS =
        Collections.emptyList();
    private static final List<ScheduleClientInterceptor> EMPTY_INTERCEPTORS =
        Collections.emptyList();
    private static final ScheduleClientPlugin[] EMPTY_PLUGINS = new ScheduleClientPlugin[0];

    private String namespace;
    private DataConverter dataConverter;
    private String identity;
    private List<ContextPropagator> contextPropagators;
    private List<ScheduleClientInterceptor> interceptors;
    private ScheduleClientPlugin[] plugins;

    private Builder() {}

    private Builder(ScheduleClientOptions options) {
      if (options == null) {
        return;
      }
      namespace = options.namespace;
      dataConverter = options.dataConverter;
      identity = options.identity;
      contextPropagators = options.contextPropagators;
      interceptors = options.interceptors;
      plugins = options.plugins;
    }

    /** Set the namespace this client will operate on. */
    public Builder setNamespace(String namespace) {
      this.namespace = namespace;
      return this;
    }

    /**
     * Overrides a data converter implementation used serialize workflow arguments and results.
     *
     * <p>Default is {@link DataConverter#getDefaultInstance()}.
     */
    public Builder setDataConverter(DataConverter dataConverter) {
      this.dataConverter = dataConverter;
      return this;
    }

    /** Override human-readable identity of the client. */
    public Builder setIdentity(String identity) {
      this.identity = identity;
      return this;
    }

    /**
     * Set the context propagators for this client.
     *
     * @param contextPropagators specifies the list of context propagators to use with the client.
     */
    public Builder setContextPropagators(List<ContextPropagator> contextPropagators) {
      this.contextPropagators = contextPropagators;
      return this;
    }

    /**
     * Set the interceptors for this client.
     *
     * @param interceptors specifies the list of interceptors to use with the client.
     */
    public Builder setInterceptors(List<ScheduleClientInterceptor> interceptors) {
      this.interceptors = interceptors;
      return this;
    }

    /**
     * Set the plugins for this client.
     *
     * @param plugins specifies the plugins to use with the client.
     */
    @Experimental
    public Builder setPlugins(ScheduleClientPlugin... plugins) {
      this.plugins = plugins;
      return this;
    }

    public ScheduleClientOptions build() {
      String name = identity == null ? ManagementFactory.getRuntimeMXBean().getName() : identity;
      return new ScheduleClientOptions(
          namespace == null ? DEFAULT_NAMESPACE : namespace,
          dataConverter == null ? GlobalDataConverter.get() : dataConverter,
          name,
          contextPropagators == null ? EMPTY_CONTEXT_PROPAGATORS : contextPropagators,
          interceptors == null ? EMPTY_INTERCEPTORS : interceptors,
          plugins == null ? EMPTY_PLUGINS : plugins);
    }
  }

  private final String namespace;
  private final DataConverter dataConverter;
  private final String identity;
  private final List<ContextPropagator> contextPropagators;
  private final List<ScheduleClientInterceptor> interceptors;
  private final ScheduleClientPlugin[] plugins;

  private ScheduleClientOptions(
      String namespace,
      DataConverter dataConverter,
      String identity,
      List<ContextPropagator> contextPropagators,
      List<ScheduleClientInterceptor> interceptors,
      ScheduleClientPlugin[] plugins) {
    this.namespace = namespace;
    this.dataConverter = dataConverter;
    this.identity = identity;
    this.contextPropagators = contextPropagators;
    this.interceptors = interceptors;
    this.plugins = plugins;
  }

  /**
   * Get the namespace this client will operate on.
   *
   * @return Client namespace
   */
  public String getNamespace() {
    return namespace;
  }

  /**
   * Get the data converters of this client
   *
   * @return The list of data converters to use with the client.
   */
  public DataConverter getDataConverter() {
    return dataConverter;
  }

  /**
   * Get the human-readable identity of the client.
   *
   * @return The identity of the client used on some requests.
   */
  public String getIdentity() {
    return identity;
  }

  /**
   * Get the context propagators of this client
   *
   * @return The list of context propagators to use with the client.
   */
  public List<ContextPropagator> getContextPropagators() {
    return contextPropagators;
  }

  /**
   * Get the interceptors of this client
   *
   * @return The list of interceptors to use with the client.
   */
  public List<ScheduleClientInterceptor> getInterceptors() {
    return interceptors;
  }

  /**
   * Get the plugins of this client
   *
   * @return The plugins to use with the client.
   */
  @Experimental
  public ScheduleClientPlugin[] getPlugins() {
    return plugins == null ? new ScheduleClientPlugin[0] : Arrays.copyOf(plugins, plugins.length);
  }
}
