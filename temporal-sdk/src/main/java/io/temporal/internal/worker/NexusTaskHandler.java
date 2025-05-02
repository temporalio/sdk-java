package io.temporal.internal.worker;

import com.uber.m3.tally.Scope;
import io.temporal.api.nexus.v1.HandlerError;
import io.temporal.api.nexus.v1.Response;
import java.util.concurrent.TimeoutException;
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
    @Nullable private final HandlerError handlerError;

    public Result(Response response) {
      this.response = response;
      handlerError = null;
    }

    public Result(HandlerError handlerError) {
      this.handlerError = handlerError;
      response = null;
    }

    @Nullable
    public Response getResponse() {
      return response;
    }

    @Nullable
    public HandlerError getHandlerError() {
      return handlerError;
    }
  }
}
