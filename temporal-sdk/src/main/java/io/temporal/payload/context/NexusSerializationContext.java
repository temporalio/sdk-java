package io.temporal.payload.context;

import io.temporal.common.Experimental;
import io.temporal.common.converter.FailureConverter;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * {@link SerializationContext} for Nexus operation payloads, identifying the Nexus endpoint,
 * service, and resolved operation the payload belongs to.
 *
 * <p>Callers receive this context when encoding operation inputs and when decoding operation
 * results and failures. Handlers receive it when decoding operation inputs, encoding synchronous
 * operation results, and encoding failures produced while handling a Nexus task.
 *
 * <p>The context is not propagated to the eventual result of an asynchronous operation, because the
 * operation is completed out of band rather than by the task the handler was invoked for. A
 * standalone operation handle uses the context of its start request, including when the start
 * request returns an already-running operation; a handle obtained by operation ID without starting
 * an operation has no endpoint, service, or operation to build a context from and therefore
 * serializes without one.
 *
 * <p>Failure conversion is not symmetric: a failure is encoded by the handler and decoded by the
 * caller, so an implementation sees this context on only one side of a given failure, and for some
 * operation paths it sees no context at all. Context-dependent encodings must therefore be
 * self-describing, and decoders must keep accepting payloads that were encoded without a context.
 * This applies to {@link FailureConverter} as much as to payload encoding.
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
