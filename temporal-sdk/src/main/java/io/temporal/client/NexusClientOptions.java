package io.temporal.client;

import io.temporal.common.Experimental;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.GlobalDataConverter;
import io.temporal.common.interceptors.NexusClientInterceptor;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.List;

/**
 * Options that configure a {@link NexusClient} (and the service-bound clients it produces).
 *
 * <p>Carries only client-wide settings (namespace, data converter, interceptors). Per-call settings
 * — operation ID, timeouts, search attributes, summary, id-reuse/conflict policies — belong on
 * {@link StartNexusOperationOptions}.
 *
 * <p>Obtain a builder via {@link #newBuilder()} or copy an existing instance via {@link
 * #newBuilder(NexusClientOptions)}. The default instance ({@link #getDefaultInstance()}) is
 * suitable when only the namespace is required and the {@link GlobalDataConverter} is appropriate.
 *
 * <pre>{@code
 * NexusClientOptions options =
 *     NexusClientOptions.newBuilder()
 *         .setNamespace("default")
 *         .setDataConverter(myDataConverter)
 *         .build();
 * }</pre>
 */
@Experimental
public class NexusClientOptions {

  private final String namespace;
  private final List<NexusClientInterceptor> interceptors;
  private final DataConverter dataConverter;
  private final String identity;

  private NexusClientOptions(
      String namespace,
      List<NexusClientInterceptor> interceptors,
      DataConverter dataConverter,
      String identity) {
    this.namespace = namespace;
    this.interceptors = interceptors;
    this.dataConverter = dataConverter;
    this.identity = identity;
  }

  /** Get the namespace this client will operate on. */
  public String getNamespace() {
    return namespace;
  }

  /** Get the interceptors of this client. */
  public List<NexusClientInterceptor> getInterceptors() {
    return interceptors;
  }

  /** Get the data converter used to serialize Nexus operation inputs and deserialize results. */
  public DataConverter getDataConverter() {
    return dataConverter;
  }

  /**
   * Human-readable identity of this client. Stamped onto outgoing write requests (start, cancel,
   * terminate) so server-side history and audit trails can attribute the action to a caller.
   */
  public String getIdentity() {
    return identity;
  }

  /** Returns a fresh builder. */
  public static NexusClientOptions.Builder newBuilder() {
    return new NexusClientOptions.Builder();
  }

  /** Returns a builder seeded with the values from {@code options}. */
  public static NexusClientOptions.Builder newBuilder(NexusClientOptions options) {
    return new NexusClientOptions.Builder(options);
  }

  private static final NexusClientOptions DEFAULT_INSTANCE;

  /**
   * Returns an options instance with all defaults. Note this leaves namespace unset; callers
   * usually need to specify a namespace.
   */
  public static NexusClientOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  static {
    DEFAULT_INSTANCE = NexusClientOptions.newBuilder().build();
  }

  /** Builder for {@link NexusClientOptions}. */
  public static class Builder {
    private String namespace;
    private List<NexusClientInterceptor> interceptors = Collections.emptyList();
    private DataConverter dataConverter = GlobalDataConverter.get();
    private String identity;

    private Builder() {}

    private Builder(NexusClientOptions options) {
      if (options == null) {
        return;
      }
      namespace = options.namespace;
      interceptors = options.interceptors;
      dataConverter = options.dataConverter;
      identity = options.identity;
    }

    /** Set the namespace this client will operate on. */
    public NexusClientOptions.Builder setNamespace(String namespace) {
      this.namespace = namespace;
      return this;
    }

    /** Set the interceptors for this client, but don't allow null lists to happen. */
    public NexusClientOptions.Builder setInterceptors(List<NexusClientInterceptor> interceptors) {
      if (interceptors == null) {
        this.interceptors = Collections.emptyList();
      } else {
        this.interceptors = interceptors;
      }
      return this;
    }

    /**
     * Set the data converter used to serialize Nexus operation inputs and deserialize results.
     * Defaults to {@link GlobalDataConverter#get()}.
     */
    public NexusClientOptions.Builder setDataConverter(DataConverter dataConverter) {
      this.dataConverter = dataConverter;
      return this;
    }

    /**
     * Override the human-readable identity stamped on outgoing write requests. Defaults to the JVM
     * runtime name (typically {@code pid@host}).
     */
    public NexusClientOptions.Builder setIdentity(String identity) {
      this.identity = identity;
      return this;
    }

    public NexusClientOptions build() {
      String resolvedIdentity =
          identity == null ? ManagementFactory.getRuntimeMXBean().getName() : identity;
      return new NexusClientOptions(namespace, interceptors, dataConverter, resolvedIdentity);
    }
  }
}
