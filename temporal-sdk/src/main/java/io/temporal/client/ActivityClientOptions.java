package io.temporal.client;

import io.temporal.common.Experimental;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.GlobalDataConverter;
import io.temporal.common.interceptors.ActivityClientInterceptor;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Options for {@link ActivityClient} configuration. */
@Experimental
public final class ActivityClientOptions {

  public static ActivityClientOptions.Builder newBuilder() {
    return new ActivityClientOptions.Builder();
  }

  public static ActivityClientOptions.Builder newBuilder(ActivityClientOptions options) {
    return new ActivityClientOptions.Builder(options);
  }

  public static ActivityClientOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  public ActivityClientOptions.Builder toBuilder() {
    return new ActivityClientOptions.Builder(this);
  }

  private static final ActivityClientOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = ActivityClientOptions.newBuilder().build();
  }

  public static final class Builder {
    private static final String DEFAULT_NAMESPACE = "default";
    private static final List<ContextPropagator> EMPTY_CONTEXT_PROPAGATORS =
        Collections.emptyList();
    private static final List<ActivityClientInterceptor> EMPTY_INTERCEPTORS =
        Collections.emptyList();

    private String namespace;
    private DataConverter dataConverter;
    private String identity;
    private List<ContextPropagator> contextPropagators;
    private List<ActivityClientInterceptor> interceptors;

    private Builder() {}

    private Builder(ActivityClientOptions options) {
      if (options == null) {
        return;
      }
      namespace = options.namespace;
      dataConverter = options.dataConverter;
      identity = options.identity;
      contextPropagators = options.contextPropagators;
      interceptors = options.interceptors;
    }

    /** Set the namespace this client will operate on. */
    public Builder setNamespace(String namespace) {
      this.namespace = namespace;
      return this;
    }

    /**
     * Overrides a data converter implementation used to serialize workflow arguments and results.
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
    public Builder setInterceptors(List<ActivityClientInterceptor> interceptors) {
      this.interceptors = interceptors;
      return this;
    }

    public ActivityClientOptions build() {
      String name = identity == null ? ManagementFactory.getRuntimeMXBean().getName() : identity;
      return new ActivityClientOptions(
          namespace == null ? DEFAULT_NAMESPACE : namespace,
          dataConverter == null ? GlobalDataConverter.get() : dataConverter,
          name,
          contextPropagators == null ? EMPTY_CONTEXT_PROPAGATORS : contextPropagators,
          interceptors == null ? EMPTY_INTERCEPTORS : interceptors);
    }
  }

  private final String namespace;
  private final DataConverter dataConverter;
  private final String identity;
  private final List<ContextPropagator> contextPropagators;
  private final List<ActivityClientInterceptor> interceptors;

  private ActivityClientOptions(
      String namespace,
      DataConverter dataConverter,
      String identity,
      List<ContextPropagator> contextPropagators,
      List<ActivityClientInterceptor> interceptors) {
    this.namespace = namespace;
    this.dataConverter = dataConverter;
    this.identity = identity;
    this.contextPropagators = contextPropagators;
    this.interceptors = interceptors;
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
   * Get the data converters of this client.
   *
   * @return The data converter to use with the client.
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
   * Get the context propagators of this client.
   *
   * @return The list of context propagators to use with the client.
   */
  public List<ContextPropagator> getContextPropagators() {
    return contextPropagators;
  }

  /**
   * Get the interceptors of this client.
   *
   * @return The list of interceptors to use with the client.
   */
  public List<ActivityClientInterceptor> getInterceptors() {
    return interceptors;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ActivityClientOptions)) return false;
    ActivityClientOptions that = (ActivityClientOptions) o;
    return Objects.equals(namespace, that.namespace)
        && Objects.equals(dataConverter, that.dataConverter)
        && Objects.equals(identity, that.identity)
        && Objects.equals(contextPropagators, that.contextPropagators)
        && Objects.equals(interceptors, that.interceptors);
  }

  @Override
  public int hashCode() {
    return Objects.hash(namespace, dataConverter, identity, contextPropagators, interceptors);
  }

  @Override
  public String toString() {
    return "ActivityClientOptions{"
        + "namespace='"
        + namespace
        + '\''
        + ", dataConverter="
        + dataConverter
        + ", identity='"
        + identity
        + '\''
        + ", contextPropagators="
        + contextPropagators
        + ", interceptors="
        + interceptors
        + '}';
  }
}
