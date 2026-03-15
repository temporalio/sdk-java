package io.temporal.internal.worker;

import com.uber.m3.tally.Scope;
import io.nexusrpc.handler.HandlerException;
import io.temporal.api.nexus.v1.Response;
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

    public Result(@Nonnull Response response) {
      Objects.requireNonNull(response);
      this.response = response;
      handlerException = null;
    }

    public Result(@Nonnull HandlerException handlerException) {
      Objects.requireNonNull(handlerException);
      this.handlerException = handlerException;
      response = null;
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
