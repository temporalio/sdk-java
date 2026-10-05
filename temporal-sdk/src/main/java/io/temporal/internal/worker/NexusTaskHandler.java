package io.temporal.internal.worker;

import com.uber.m3.tally.Scope;
import io.nexusrpc.handler.HandlerException;
import io.temporal.api.nexus.v1.Response;
import io.temporal.payload.context.NexusSerializationContext;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface NexusTaskHandler {

  /**
   * Start the handler if the handler has any registered services. It is an error to start a handler
   * more than once.
   *
   * @return True if this handler can handle at least one nexus service.
   */
  boolean start();

  NexusTaskHandler.Result handle(NexusTask task, Scope metricsScope) throws TimeoutException;

  class Result {
    @Nullable private final Response response;
    @Nullable private final HandlerException handlerException;
    // Serialization context of the operation the task was for. Carried on the result because the
    // reply is encoded after the handler has returned, by which point the per-task context is no
    // longer in scope. Null when the task named no operation, or when the server did not report
    // the endpoint it was addressed to.
    @Nullable private final NexusSerializationContext serializationContext;

    public Result(@Nonnull Response response) {
      Objects.requireNonNull(response);
      this.response = response;
      handlerException = null;
      serializationContext = null;
    }

    public Result(@Nonnull HandlerException handlerException) {
      this(handlerException, null);
    }

    public Result(
        @Nonnull HandlerException handlerException,
        @Nullable NexusSerializationContext serializationContext) {
      Objects.requireNonNull(handlerException);
      this.handlerException = handlerException;
      this.serializationContext = serializationContext;
      response = null;
    }

    /**
     * Serialization context to encode {@link #getHandlerException()} with, or null to encode it
     * without one.
     */
    @Nullable
    public NexusSerializationContext getSerializationContext() {
      return serializationContext;
    }

    @Nullable
    public Response getResponse() {
      return response;
    }

    @Nullable
    public HandlerException getHandlerException() {
      return handlerException;
    }
  }
}
