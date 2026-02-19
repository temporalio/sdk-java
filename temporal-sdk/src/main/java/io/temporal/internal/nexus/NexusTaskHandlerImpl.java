package io.temporal.internal.nexus;

import static io.temporal.internal.common.NexusUtil.nexusProtoLinkToLink;

import com.uber.m3.tally.Scope;
import io.grpc.StatusRuntimeException;
import io.nexusrpc.Header;
import io.nexusrpc.OperationException;
import io.nexusrpc.OperationState;
import io.nexusrpc.handler.*;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.nexus.v1.*;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.TemporalFailure;
import io.temporal.internal.common.InternalUtils;
import io.temporal.internal.common.NexusUtil;
import io.temporal.internal.worker.NexusTask;
import io.temporal.internal.worker.NexusTaskHandler;
import io.temporal.internal.worker.ShutdownManager;
import io.temporal.serviceclient.CheckedExceptionWrapper;
import io.temporal.worker.TypeAlreadyRegisteredException;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NexusTaskHandlerImpl implements NexusTaskHandler {
  private static final Logger log = LoggerFactory.getLogger(NexusTaskHandlerImpl.class);

  private final DataConverter dataConverter;
  private final String namespace;
  private final String taskQueue;
  private final WorkflowClient client;
  private ServiceHandler serviceHandler;
  private final Map<String, ServiceImplInstance> serviceImplInstances =
      Collections.synchronizedMap(new HashMap<>());
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
  private final TemporalInterceptorMiddleware nexusServiceInterceptor;

  public NexusTaskHandlerImpl(
      @Nonnull WorkflowClient client,
      @Nonnull String namespace,
      @Nonnull String taskQueue,
      @Nonnull DataConverter dataConverter,
      @Nonnull WorkerInterceptor[] interceptors) {
    this.client = Objects.requireNonNull(client);
    this.namespace = Objects.requireNonNull(namespace);
    this.taskQueue = Objects.requireNonNull(taskQueue);
    this.dataConverter = Objects.requireNonNull(dataConverter);
    Objects.requireNonNull(interceptors);
    this.nexusServiceInterceptor = new TemporalInterceptorMiddleware(interceptors);
  }

  @Override
  public boolean start() {
    if (serviceImplInstances.isEmpty()) {
      return false;
    }
    ServiceHandler.Builder serviceHandlerBuilder =
        ServiceHandler.newBuilder().setSerializer(new PayloadSerializer(dataConverter));
    serviceImplInstances.forEach((name, instance) -> serviceHandlerBuilder.addInstance(instance));
    serviceHandlerBuilder.addOperationMiddleware(nexusServiceInterceptor);
    serviceHandler = serviceHandlerBuilder.build();
    return true;
  }

  @Override
  public Result handle(NexusTask task, Scope metricsScope) throws TimeoutException {
    Request request = task.getResponse().getRequest();
    Map<String, String> headers = request.getHeaderMap();

    OperationContext.Builder ctx = OperationContext.newBuilder();
    headers.forEach(ctx::putHeader);
    OperationMethodCanceller canceller = new OperationMethodCanceller();
    ctx.setMethodCanceller(canceller);

    ScheduledFuture<?> timeoutTask = null;
    AtomicBoolean timedOut = new AtomicBoolean(false);
    try {
      // Parse request timeout, use the context headers to get the timeout
      // since they are case-insensitive.
      String timeoutString = ctx.getHeaders().get(Header.REQUEST_TIMEOUT);
      if (timeoutString != null) {
        try {
          Duration timeout = NexusUtil.parseRequestTimeout(timeoutString);
          timeoutTask =
              scheduler.schedule(
                  () -> {
                    timedOut.set(true);
                    canceller.cancel("timeout");
                  },
                  timeout.toMillis(),
                  java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (IllegalArgumentException e) {
          throw new HandlerException(
              HandlerException.ErrorType.BAD_REQUEST,
              new RuntimeException("Invalid request timeout header", e));
        }
      }

      CurrentNexusOperationContext.set(
          new InternalNexusOperationContext(namespace, taskQueue, metricsScope, client));

      switch (request.getVariantCase()) {
        case START_OPERATION:
          StartOperationResponse startResponse =
              handleStartOperation(ctx, request.getStartOperation());
          return new Result(Response.newBuilder().setStartOperation(startResponse).build());
        case CANCEL_OPERATION:
          CancelOperationResponse cancelResponse =
              handleCancelledOperation(ctx, request.getCancelOperation());
          return new Result(Response.newBuilder().setCancelOperation(cancelResponse).build());
        default:
          throw new HandlerException(
              HandlerException.ErrorType.NOT_IMPLEMENTED,
              new RuntimeException("Unknown request type: " + request.getVariantCase()));
      }
    } catch (HandlerException e) {
      return new Result(e);
    } catch (Throwable e) {
      return new Result(new HandlerException(HandlerException.ErrorType.INTERNAL, e));
    } finally {
      // If the task timed out, we should not send a response back to the server
      if (timedOut.get()) {
        throw new TimeoutException("Nexus task completed after timeout.");
      }
      canceller.cancel("");
      if (timeoutTask != null) {
        timeoutTask.cancel(false);
      }
      CurrentNexusOperationContext.unset();
    }
  }

  private void cancelOperation(OperationContext context, OperationCancelDetails details) {
    try {
      serviceHandler.cancelOperation(context, details);
    } catch (Throwable e) {
      Throwable failure = CheckedExceptionWrapper.unwrap(e);
      log.warn(
          "Nexus cancel operation failure. Service={}, Operation={}",
          context.getService(),
          context.getOperation(),
          failure);
      // Re-throw the original exception to handle it in the caller
      throw e;
    }
  }

  @SuppressWarnings("deprecation") // Continue to check operation id for history compatibility
  private CancelOperationResponse handleCancelledOperation(
      OperationContext.Builder ctx, CancelOperationRequest task) {
    ctx.setService(task.getService()).setOperation(task.getOperation());

    @SuppressWarnings("deprecation") // getOperationId kept to support old server for a while
    OperationCancelDetails operationCancelDetails =
        OperationCancelDetails.newBuilder()
            .setOperationToken(
                task.getOperationToken().isEmpty()
                    ? task.getOperationId()
                    : task.getOperationToken())
            .build();
    try {
      cancelOperation(ctx.build(), operationCancelDetails);
    } catch (Throwable failure) {
      convertKnownFailures(failure);
    }

    return CancelOperationResponse.newBuilder().build();
  }

  private void convertKnownFailures(Throwable e) {
    Throwable failure = CheckedExceptionWrapper.unwrap(e);
    if (failure instanceof WorkflowException) {
      if (failure instanceof WorkflowNotFoundException) {
        throw new HandlerException(HandlerException.ErrorType.NOT_FOUND, failure);
      }
      throw new HandlerException(HandlerException.ErrorType.BAD_REQUEST, failure);
    }
    if (failure instanceof ApplicationFailure) {
      if (((ApplicationFailure) failure).isNonRetryable()) {
        throw new HandlerException(
            HandlerException.ErrorType.INTERNAL,
            failure,
            HandlerException.RetryBehavior.NON_RETRYABLE);
      }
    }
    if (failure instanceof StatusRuntimeException) {
      StatusRuntimeException statusRuntimeException = (StatusRuntimeException) failure;
      throw convertStatusRuntimeExceptionToHandlerException(statusRuntimeException);
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    throw failure instanceof RuntimeException
        ? (RuntimeException) failure
        : new RuntimeException(failure);
  }

  private HandlerException convertStatusRuntimeExceptionToHandlerException(
      StatusRuntimeException sre) {
    switch (sre.getStatus().getCode()) {
      case INVALID_ARGUMENT:
        return new HandlerException(HandlerException.ErrorType.BAD_REQUEST, sre);
      case ALREADY_EXISTS:
      case FAILED_PRECONDITION:
      case OUT_OF_RANGE:
        return new HandlerException(
            HandlerException.ErrorType.INTERNAL, sre, HandlerException.RetryBehavior.NON_RETRYABLE);
      case ABORTED:
      case UNAVAILABLE:
        return new HandlerException(HandlerException.ErrorType.UNAVAILABLE, sre);
      case CANCELLED:
      case DATA_LOSS:
      case INTERNAL:
      case UNKNOWN:
      case UNAUTHENTICATED:
      case PERMISSION_DENIED:
        // Note that codes.Unauthenticated, codes.PermissionDenied have Nexus error types but we
        // convert to internal
        // because this is not a client auth error and happens when the handler fails to auth with
        // Temporal and should
        // be considered retryable.
        return new HandlerException(HandlerException.ErrorType.INTERNAL, sre);
      case NOT_FOUND:
        return new HandlerException(HandlerException.ErrorType.NOT_FOUND, sre);
      case RESOURCE_EXHAUSTED:
        return new HandlerException(HandlerException.ErrorType.RESOURCE_EXHAUSTED, sre);
      case UNIMPLEMENTED:
        return new HandlerException(HandlerException.ErrorType.NOT_IMPLEMENTED, sre);
      case DEADLINE_EXCEEDED:
        return new HandlerException(HandlerException.ErrorType.UPSTREAM_TIMEOUT, sre);
      default:
        // If the status code is not recognized, we treat it as an internal error
        return new HandlerException(HandlerException.ErrorType.INTERNAL, sre);
    }
  }

  private OperationStartResult<HandlerResultContent> startOperation(
      OperationContext context, OperationStartDetails details, HandlerInputContent input)
      throws OperationException {
    try {
      return serviceHandler.startOperation(context, details, input);
    } catch (Throwable e) {
      Throwable ex = CheckedExceptionWrapper.unwrap(e);
      log.warn(
          "Nexus start operation failure. Service={}, Operation={}",
          context.getService(),
          context.getOperation(),
          ex);
      // Re-throw the original exception to handle it in the caller
      throw e;
    }
  }

  @SuppressWarnings("deprecation") // Continue to check operation id for history compatibility
  private StartOperationResponse handleStartOperation(
      OperationContext.Builder ctx, StartOperationRequest task) {
    ctx.setService(task.getService()).setOperation(task.getOperation());

    OperationStartDetails.Builder operationStartDetails =
        OperationStartDetails.newBuilder()
            .setCallbackUrl(task.getCallback())
            .setRequestId(task.getRequestId());
    task.getCallbackHeaderMap().forEach(operationStartDetails::putCallbackHeader);
    task.getLinksList()
        .forEach(
            link -> {
              try {
                operationStartDetails.addLink(nexusProtoLinkToLink(link));
              } catch (URISyntaxException e) {
                log.error("failed to parse link url: " + link.getUrl(), e);
                throw new HandlerException(
                    HandlerException.ErrorType.BAD_REQUEST,
                    new RuntimeException("Invalid link URL: " + link.getUrl(), e));
              }
            });

    HandlerInputContent.Builder input =
        HandlerInputContent.newBuilder().setDataStream(task.getPayload().toByteString().newInput());

    StartOperationResponse.Builder startResponseBuilder = StartOperationResponse.newBuilder();
    OperationContext context = ctx.build();
    try {
      try {
        OperationStartResult<HandlerResultContent> result =
            startOperation(context, operationStartDetails.build(), input.build());
        if (result.isSync()) {
          startResponseBuilder.setSyncSuccess(
              StartOperationResponse.Sync.newBuilder()
                  .setPayload(Payload.parseFrom(result.getSyncResult().getDataBytes()))
                  .build());
        } else {
          startResponseBuilder.setAsyncSuccess(
              StartOperationResponse.Async.newBuilder()
                  .setOperationId(result.getAsyncOperationToken())
                  .setOperationToken(result.getAsyncOperationToken())
                  .addAllLinks(
                      context.getLinks().stream()
                          .map(
                              link ->
                                  io.temporal.api.nexus.v1.Link.newBuilder()
                                      .setType(link.getType())
                                      .setUrl(link.getUri().toString())
                                      .build())
                          .collect(Collectors.toList()))
                  .build());
        }
      } catch (OperationException e) {
        throw e;
      } catch (Throwable failure) {
        convertKnownFailures(failure);
      }
    } catch (OperationException e) {
      TemporalFailure temporalFailure;
      if (e.getState() == OperationState.FAILED) {
        temporalFailure =
            ApplicationFailure.newFailureWithCause(e.getMessage(), "OperationError", e.getCause());
        temporalFailure.setStackTrace(e.getStackTrace());
      } else if (e.getState() == OperationState.CANCELED) {
        temporalFailure =
            new CanceledFailure(e.getMessage(), new EncodedValues(null), e.getCause());
        temporalFailure.setStackTrace(e.getStackTrace());
      } else {
        throw new HandlerException(
            HandlerException.ErrorType.INTERNAL,
            new RuntimeException("Unknown operation state: " + e.getState()));
      }
      startResponseBuilder.setFailure(dataConverter.exceptionToFailure(temporalFailure));
    }
    return startResponseBuilder.build();
  }

  public void registerNexusServiceImplementations(Object[] nexusServiceImplementation) {
    for (Object nexusService : nexusServiceImplementation) {
      registerNexusService(nexusService);
    }
  }

  private void registerNexusService(Object nexusService) {
    if (nexusService instanceof Class) {
      throw new IllegalArgumentException("Nexus service object instance expected, not the class");
    }
    ServiceImplInstance instance = ServiceImplInstance.fromInstance(nexusService);
    InternalUtils.checkMethodName(instance);
    if (serviceImplInstances.put(instance.getDefinition().getName(), instance) != null) {
      throw new TypeAlreadyRegisteredException(
          instance.getDefinition().getName(),
          "\""
              + instance.getDefinition().getName()
              + "\" service type is already registered with the worker");
    }
  }

  public CompletionStage<Void> shutdown(ShutdownManager shutdownManager, boolean unused) {
    return shutdownManager.shutdownExecutorNow(
        scheduler, "NexusTaskHandlerImpl#scheduler", Duration.ofSeconds(5));
  }
}
