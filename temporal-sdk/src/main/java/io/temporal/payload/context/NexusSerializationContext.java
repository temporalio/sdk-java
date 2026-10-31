package io.temporal.payload.context;

import io.temporal.common.Experimental;
import io.temporal.common.converter.FailureConverter;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * {@link SerializationContext} for Nexus operation payloads, identifying the Nexus endpoint,
 * service, and resolved operation the payload belongs to.
 *
 * <p>This context is used for payloads exchanged between a Nexus caller and handler: operation
 * inputs, results, and failures. When a handler starts a workflow or standalone activity, or sends
 * a workflow update, to back an operation, whatever it returns to the caller, such as its result or
 * failure, is serialized with this context too, so the caller can decode it. Everything else keeps
 * the workflow's or activity's own context. This requires a server that propagates the context.
 *
 * <p>To complete such an activity asynchronously, store the context from {@link
 * io.temporal.activity.ActivityInfo#getNexusSerializationContext()} with the task token and pass it
 * to {@link io.temporal.client.ActivityCompletionClient#withContext(NexusSerializationContext)}.
 *
 * <p>Some payloads, such as failures and payloads from older servers, may be serialized without
 * this context. Context-dependent encodings, including in a {@link FailureConverter}, should be
 * self-describing, and decoders should accept payloads encoded without a context.
 */
@Experimental
public final class NexusSerializationContext implements SerializationContext {
  private final @Nonnull String endpoint;
  private final @Nonnull String service;
  private final @Nonnull String operation;

  /**
   * @param endpoint the Nexus endpoint name; must not be {@code null}
   * @param service the Nexus service name; must not be {@code null}
   * @param operation the resolved Nexus operation name; must not be {@code null}
   */
  public NexusSerializationContext(
      @Nonnull String endpoint, @Nonnull String service, @Nonnull String operation) {
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    this.service = Objects.requireNonNull(service, "service");
    this.operation = Objects.requireNonNull(operation, "operation");
  }

  /**
   * @return the Nexus endpoint name
   */
  @Nonnull
  public String getEndpoint() {
    return endpoint;
  }

  /**
   * @return the Nexus service name
   */
  @Nonnull
  public String getService() {
    return service;
  }

  /**
   * @return the resolved Nexus operation name
   */
  @Nonnull
  public String getOperation() {
    return operation;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof NexusSerializationContext)) {
      return false;
    }
    NexusSerializationContext that = (NexusSerializationContext) o;
    return endpoint.equals(that.endpoint)
        && service.equals(that.service)
        && operation.equals(that.operation);
  }

  @Override
  public int hashCode() {
    return Objects.hash(endpoint, service, operation);
  }

  @Override
  public String toString() {
    return "NexusSerializationContext{"
        + "endpoint='"
        + endpoint
        + '\''
        + ", service='"
        + service
        + '\''
        + ", operation='"
        + operation
        + '\''
        + '}';
  }
}
