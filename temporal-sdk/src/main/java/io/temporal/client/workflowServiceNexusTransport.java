package io.temporal.client;

import static io.temporal.internal.common.NexusUtil.exceptionToNexusFailure;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.nexusrpc.*;
import io.nexusrpc.client.transport.*;
import io.nexusrpc.handler.HandlerException;
import io.temporal.api.common.v1.Callback;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.ApplicationFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.failure.v1.NexusHandlerFailureInfo;
import io.temporal.api.nexus.v1.HandlerError;
import io.temporal.api.nexus.v1.TaskDispatchTarget;
import io.temporal.api.nexus.v1.UnsuccessfulOperationError;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.common.NexusUtil;
import io.temporal.internal.common.ProtobufTimeUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * workflowServiceNexusTransport is a transport implementation for the Nexus API that is backed by
 * the Temporal workflow service gRPC API.
 */
public class workflowServiceNexusTransport implements Transport {
  private final GenericWorkflowClient client;
  private final WorkflowClientOptions clientOptions;
  private final TaskDispatchTarget dispatchTarget;

  public workflowServiceNexusTransport(
      GenericWorkflowClient client, TemporalNexusServiceClientOptions serviceClientOptions, WorkflowClientOptions options) {
    this.client = client;
    this.clientOptions = options;
    if (serviceClientOptions.getEndpoint() != null) {
      this.dispatchTarget = TaskDispatchTarget.newBuilder().setEndpoint(serviceClientOptions.getEndpoint()).build();
    } else if (serviceClientOptions.getTaskQueue() != null) {
      this.dispatchTarget = TaskDispatchTarget.newBuilder().setTaskQueue(serviceClientOptions.getTaskQueue()).build();
    } else {
      throw new IllegalArgumentException("No target specified");
    }
  }

  private static final JsonFormat.Parser JSON_PARSER = JsonFormat.parser();

  private static final String FAILURE_TYPE_STRING = Failure.getDescriptor().getFullName();

  private static Failure handlerErrorToFailure(HandlerError err) {
    return Failure.newBuilder()
        .setMessage(err.getFailure().getMessage())
        .setNexusHandlerFailureInfo(
            NexusHandlerFailureInfo.newBuilder()
                .setType(err.getErrorType())
                .setRetryBehavior(err.getRetryBehavior())
                .build())
        .setCause(nexusFailureToAPIFailure(err.getFailure(), false))
        .build();
  }

  private static Failure nexusFailureToAPIFailure(
      io.temporal.api.nexus.v1.Failure failure, boolean retryable) {
    Failure.Builder apiFailure = Failure.newBuilder();
    if (failure.getMetadataMap().containsKey("type")
        && failure.getMetadataMap().get("type").equals(FAILURE_TYPE_STRING)) {
      try {
        JSON_PARSER.merge(failure.getDetails().toString(UTF_8), apiFailure);
      } catch (InvalidProtocolBufferException e) {
        throw new RuntimeException(e);
      }
    } else {
      Payloads payloads = nexusFailureMetadataToPayloads(failure);
      ApplicationFailureInfo.Builder applicationFailureInfo = ApplicationFailureInfo.newBuilder();
      applicationFailureInfo.setType("NexusFailure");
      applicationFailureInfo.setDetails(payloads);
      applicationFailureInfo.setNonRetryable(!retryable);
      apiFailure.setApplicationFailureInfo(applicationFailureInfo.build());
    }
    apiFailure.setMessage(failure.getMessage());
    return apiFailure.build();
  }

  private static Payloads nexusFailureMetadataToPayloads(io.temporal.api.nexus.v1.Failure failure) {
    Map<String, ByteString> metadata =
        failure.getMetadataMap().entrySet().stream()
            .collect(
                Collectors.toMap(Map.Entry::getKey, e -> ByteString.copyFromUtf8(e.getValue())));
    return Payloads.newBuilder()
        .addPayloads(Payload.newBuilder().putAllMetadata(metadata).setData(failure.getDetails()))
        .build();
  }

  private OperationState deserializeOperationState(String state) {
    switch (state) {
      case "running":
        return OperationState.RUNNING;
      case "succeeded":
        return OperationState.SUCCEEDED;
      case "failed":
        return OperationState.FAILED;
      case "canceled":
        return OperationState.CANCELED;
      default:
        throw new IllegalArgumentException("Unknown operation state: " + state);
    }
  }

  private StartNexusOperationRequest createStartOperationRequest(
      String operationName, String serviceName, Object input, StartOperationOptions options) {
    StartNexusOperationRequest.Builder request =
        StartNexusOperationRequest.newBuilder()
            .setIdentity(clientOptions.getIdentity())
            .setNamespace(clientOptions.getNamespace())
            .setTarget(dispatchTarget)
            .setOperation(operationName)
            .setService(serviceName)
            .putAllCallbackHeader(options.getCallbackHeaders())
            .putAllHeader(options.getHeaders());

    if (Strings.isNullOrEmpty(options.getRequestId())) {
      request.setRequestId(UUID.randomUUID().toString());
    } else {
      request.setRequestId(options.getRequestId());
    }

    if (!Strings.isNullOrEmpty(options.getCallbackURL())) {
      request.setCallback(options.getCallbackURL());
    }

    clientOptions.getDataConverter().toPayload(input).ifPresent(request::setPayload);

    options.getInboundLinks().stream()
        .map(
            link ->
                io.temporal.api.nexus.v1.Link.newBuilder()
                    .setType(link.getType())
                    .setUrl(link.getUri().toString())
                    .build())
        .forEach(request::addLinks);
    return request.build();
  }

  private StartOperationResponse createStartOperationResponse(StartNexusOperationResponse response)
      throws OperationException {
    if (response.hasSyncSuccess()) {
      StartNexusOperationResponse.Sync syncResult = response.getSyncSuccess();
      return StartOperationResponse.newBuilder()
          .setResult(
              Serializer.Content.newBuilder().setData(syncResult.getResult().toByteArray()).build())
          .build();
    } else if (response.hasAsyncSuccess()) {
      StartNexusOperationResponse.Async asyncResult = response.getAsyncSuccess();
      return StartOperationResponse.newBuilder()
          .setAsyncOperationToken(asyncResult.getOperationToken())
          .build();
    } else if (response.hasUnsuccessful()) {
      StartNexusOperationResponse.Unsuccessful unsuccessful = response.getUnsuccessful();
      UnsuccessfulOperationError error = unsuccessful.getOperationError();
      Throwable cause =
          clientOptions
              .getDataConverter()
              .failureToException(nexusFailureToAPIFailure(error.getFailure(), false));
      if (error.getOperationState().equals("canceled")) {
        throw OperationException.canceled(cause);
      } else {
        throw OperationException.failure(cause);
      }
    } else if (response.hasHandlerError()) {
      HandlerError error = response.getHandlerError();
      throw clientOptions.getDataConverter().failureToException(handlerErrorToFailure(error));
    } else {
      throw new IllegalStateException("Unknown response from startNexusCall: " + response);
    }
  }

  @Override
  public StartOperationResponse startOperation(
      String operationName, String serviceName, Object input, StartOperationOptions options)
      throws OperationException {
    try {
      StartNexusOperationResponse response =
          client.startNexusOperation(
              createStartOperationRequest(operationName, serviceName, input, options));
      return createStartOperationResponse(response);
    } catch (StatusRuntimeException sre) {
      throw NexusUtil.grpcExceptionToHandlerException(sre);
    }
  }

  private GetNexusOperationResultRequest createGetNexusOperationResultRequest(
      String operationName,
      String serviceName,
      String operationToken,
      FetchOperationResultOptions options) {
    GetNexusOperationResultRequest.Builder request =
        GetNexusOperationResultRequest.newBuilder()
            .setIdentity(clientOptions.getIdentity())
            .setNamespace(clientOptions.getNamespace())
            .setOperation(operationName)
            .setService(serviceName)
            .setTarget(dispatchTarget)
            .setOperationToken(operationToken)
            .setWait(ProtobufTimeUtils.toProtoDuration(options.getTimeout()));

    options.getHeaders().forEach(request::putHeader);

    return request.build();
  }

  private FetchOperationResultResponse createGetOperationResultResponse(
      GetNexusOperationResultResponse response)
      throws OperationException, OperationStillRunningException {
    if (response.hasSuccessful()) {
      GetNexusOperationResultResponse.Successful successful = response.getSuccessful();
      return FetchOperationResultResponse.newBuilder()
          .setResult(
              Serializer.Content.newBuilder().setData(successful.getResult().toByteArray()).build())
          .build();
    } else if (response.hasUnsuccessful()) {
      GetNexusOperationResultResponse.Unsuccessful unsuccessful = response.getUnsuccessful();
      UnsuccessfulOperationError error = unsuccessful.getOperationError();
      Throwable cause =
          clientOptions
              .getDataConverter()
              .failureToException(nexusFailureToAPIFailure(error.getFailure(), false));
      if (error.getOperationState().equals("canceled")) {
        throw OperationException.canceled(cause);
      } else {
        throw OperationException.failure(cause);
      }
    } else if (response.hasHandlerError()) {
      HandlerError error = response.getHandlerError();
      throw clientOptions.getDataConverter().failureToException(handlerErrorToFailure(error));
    } else if (response.hasStillRunning()) {
      throw new OperationStillRunningException();
    } else {
      throw new IllegalStateException("Unknown response from startNexusCall: " + response);
    }
  }

  @Override
  public FetchOperationResultResponse fetchOperationResult(
      String operationName,
      String serviceName,
      String operationToken,
      FetchOperationResultOptions options)
      throws OperationException, OperationStillRunningException {
    Instant startTime = Instant.now();
    while (true) {
      try {
        try {
          GetNexusOperationResultResponse response =
              client.getNexusOperationResult(
                  createGetNexusOperationResultRequest(
                      operationName, serviceName, operationToken, options));
          return createGetOperationResultResponse(response);
        } catch (StatusRuntimeException sre) {
          throw NexusUtil.grpcExceptionToHandlerException(sre);
        }
      } catch (OperationStillRunningException e) {
        // If the operation is still running, we wait for the specified timeout before retrying.
        if (Instant.now().isAfter(startTime.plus(options.getTimeout()))) {
          throw e; // Timeout reached, rethrow the exception.
        }
        // TODO implement exponential backoff or other retry strategies.
      }
    }
  }

  private GetNexusOperationInfoRequest createGetNexusOperationInfoRequest(
      String operationName,
      String serviceName,
      String operationToken,
      FetchOperationInfoOptions options) {
    GetNexusOperationInfoRequest.Builder request =
        GetNexusOperationInfoRequest.newBuilder()
            .setIdentity(clientOptions.getIdentity())
            .setNamespace(clientOptions.getNamespace())
            .setTarget(dispatchTarget)
            .setOperation(operationName)
            .setService(serviceName)
            .setOperationToken(operationToken);

    options.getHeaders().forEach(request::putHeader);

    return request.build();
  }

  private FetchOperationInfoResponse createGetOperationInfoResponse(
      GetNexusOperationInfoResponse response) {
    if (response.hasHandlerError()) {
      HandlerError error = response.getHandlerError();
      throw clientOptions.getDataConverter().failureToException(handlerErrorToFailure(error));
    }

    return FetchOperationInfoResponse.newBuilder()
        .setOperationInfo(
            OperationInfo.newBuilder()
                .setToken(response.getInfo().getToken())
                .setState(deserializeOperationState(response.getInfo().getState()))
                .build())
        .build();
  }

  @Override
  public FetchOperationInfoResponse fetchOperationInfo(
      String operationName,
      String serviceName,
      String operationToken,
      FetchOperationInfoOptions options) {
    try {
      return createGetOperationInfoResponse(
          client.getNexusOperationInfo(
              createGetNexusOperationInfoRequest(
                  operationName, serviceName, operationToken, options)));
    } catch (StatusRuntimeException sre) {
      throw NexusUtil.grpcExceptionToHandlerException(sre);
    }
  }

  private RequestCancelNexusOperationRequest createRequestCancelNexusOperationRequest(
      String operationName,
      String serviceName,
      String operationToken,
      CancelOperationOptions options) {
    RequestCancelNexusOperationRequest.Builder request =
        RequestCancelNexusOperationRequest.newBuilder()
            .setIdentity(clientOptions.getIdentity())
            .setNamespace(clientOptions.getNamespace())
            .setTarget(dispatchTarget)
            .setOperation(operationName)
            .setService(serviceName)
            .setOperationToken(operationToken);

    options.getHeaders().forEach(request::putHeader);

    return request.build();
  }

  private CancelOperationResponse createRequestCancelNexusOperationResponse(
      RequestCancelNexusOperationResponse response) {
    if (response.hasHandlerError()) {
      HandlerError error = response.getHandlerError();
      throw clientOptions.getDataConverter().failureToException(handlerErrorToFailure(error));
    }

    return new CancelOperationResponse();
  }

  @Override
  public CancelOperationResponse cancelOperation(
      String operationName,
      String serviceName,
      String operationToken,
      CancelOperationOptions options) {
    try {
      return createRequestCancelNexusOperationResponse(
          client.requestCancelNexusOperation(
              createRequestCancelNexusOperationRequest(
                  operationName, serviceName, operationToken, options)));
    } catch (StatusRuntimeException sre) {
      throw NexusUtil.grpcExceptionToHandlerException(sre);
    }
  }

  private CompleteNexusOperationRequest createCompleteNexusOperationRequest(
      String url, CompleteOperationOptions options) {
    Callback.Nexus.Builder callbackBuilder = Callback.Nexus.newBuilder().setUrl(url);
    if (options.getHeaders() != null) {
      callbackBuilder.putAllHeader(options.getHeaders());
    }

    CompleteNexusOperationRequest.Builder request =
        CompleteNexusOperationRequest.newBuilder()
            .setIdentity(clientOptions.getIdentity())
            .setNamespace(clientOptions.getNamespace())
            .setCallback(callbackBuilder.build());

    request.setRequestId(UUID.randomUUID().toString());

    if (options.getStartTime() != null) {
      request.setStartedTime(ProtobufTimeUtils.toProtoTimestamp(options.getStartTime()));
    }

    if (options.getLinks() != null) {
      options.getLinks().stream()
          .map(
              link ->
                  io.temporal.api.nexus.v1.Link.newBuilder()
                      .setType(link.getType())
                      .setUrl(link.getUri().toString())
                      .build())
          .forEach(request::addLinks);
    }

    if (options.getResult() != null) {
      request.setResult(clientOptions.getDataConverter().toPayload(options.getResult()).get());
    } else if (options.getError() != null) {
      OperationException operationException = options.getError();
      request.setOperationError(
          UnsuccessfulOperationError.newBuilder()
              .setOperationState(options.getError().getState().toString().toLowerCase())
              .setFailure(
                  exceptionToNexusFailure(operationException, clientOptions.getDataConverter()))
              .build());
    }
    return request.build();
  }

  private CompleteOperationResponse createCompleteOperationResponse(
      CompleteNexusOperationResponse response) {
    if (response.hasHandlerError()) {
      HandlerError error = response.getHandlerError();
      throw clientOptions.getDataConverter().failureToException(handlerErrorToFailure(error));
    }

    return new CompleteOperationResponse();
  }

  @Override
  public CompleteOperationResponse completeOperation(String url, CompleteOperationOptions options) {
    try {
      return createCompleteOperationResponse(
          client.completeNexusOperation(createCompleteNexusOperationRequest(url, options)));
    } catch (StatusRuntimeException sre) {
      throw NexusUtil.grpcExceptionToHandlerException(sre);
    }
  }

  @Override
  public CompletableFuture<StartOperationResponse> startOperationAsync(
      String operationName, String serviceName, Object input, StartOperationOptions options) {
    return client
        .startNexusOperationAsync(
            createStartOperationRequest(operationName, serviceName, input, options))
        .thenApply(
            response -> {
              try {
                return createStartOperationResponse(response);
              } catch (OperationException e) {
                throw new CompletionException(e);
              }
            })
        .exceptionally(
            ex -> {
              if (ex.getCause() instanceof StatusRuntimeException) {
                throw NexusUtil.grpcExceptionToHandlerException(
                    (StatusRuntimeException) ex.getCause());
              } else if (ex instanceof RuntimeException) {
                throw (RuntimeException) ex;
              } else {
                throw new CompletionException(ex);
              }
            });
  }

  private CompletableFuture<FetchOperationResultResponse> waitForResult(
      Instant startTime,
      Duration timeout,
      GetNexusOperationResultRequest request,
      GetNexusOperationResultResponse response) {
    try {
      return CompletableFuture.completedFuture(createGetOperationResultResponse(response));
    } catch (OperationException e) {
      throw new CompletionException(e);
    } catch (OperationStillRunningException e) {
      if (Instant.now().isAfter(startTime.plus(timeout))) {
        throw new CompletionException(e);
      }
      return client
          .getNexusOperationResultAsync(request)
          .thenComposeAsync(r -> waitForResult(startTime, timeout, request, r));
    }
  }

  @Override
  public CompletableFuture<FetchOperationResultResponse> fetchOperationResultAsync(
      String operationName,
      String serviceName,
      String operationToken,
      FetchOperationResultOptions options) {
    Instant startTime = Instant.now();
    GetNexusOperationResultRequest request =
        createGetNexusOperationResultRequest(operationName, serviceName, operationToken, options);
    CompletableFuture<GetNexusOperationResultResponse> response =
        client.getNexusOperationResultAsync(request);
    return response
        .thenComposeAsync(r -> waitForResult(startTime, options.getTimeout(), request, r))
        .exceptionally(
            ex -> {
              if (ex.getCause() instanceof StatusRuntimeException) {
                throw NexusUtil.grpcExceptionToHandlerException(
                    (StatusRuntimeException) ex.getCause());
              } else if (ex instanceof RuntimeException) {
                throw (RuntimeException) ex;
              } else {
                throw new CompletionException(ex);
              }
            });
  }

  @Override
  public CompletableFuture<FetchOperationInfoResponse> fetchOperationInfoAsync(
      String operationName,
      String serviceName,
      String operationToken,
      FetchOperationInfoOptions options) {
    return client
        .getNexusOperationInfoAsync(
            createGetNexusOperationInfoRequest(operationName, serviceName, operationToken, options))
        .thenApply(this::createGetOperationInfoResponse)
        .exceptionally(
            ex -> {
              if (ex.getCause() instanceof StatusRuntimeException) {
                throw NexusUtil.grpcExceptionToHandlerException(
                    (StatusRuntimeException) ex.getCause());
              } else if (ex instanceof RuntimeException) {
                throw (RuntimeException) ex;
              } else {
                throw new CompletionException(ex);
              }
            });
  }

  @Override
  public CompletableFuture<CancelOperationResponse> cancelOperationAsync(
      String operationName,
      String serviceName,
      String operationToken,
      CancelOperationOptions options) {
    return client
        .requestCancelNexusOperationAsync(
            createRequestCancelNexusOperationRequest(
                operationName, serviceName, operationToken, options))
        .thenApply(this::createRequestCancelNexusOperationResponse)
        .exceptionally(
            ex -> {
              if (ex.getCause() instanceof StatusRuntimeException) {
                throw NexusUtil.grpcExceptionToHandlerException(
                    (StatusRuntimeException) ex.getCause());
              } else if (ex instanceof RuntimeException) {
                throw (RuntimeException) ex;
              } else {
                throw new CompletionException(ex);
              }
            });
  }

  @Override
  public CompletableFuture<CompleteOperationResponse> completeOperationAsync(
      String operationToken, CompleteOperationOptions options) {
    return client
        .completeNexusOperationAsync(createCompleteNexusOperationRequest(operationToken, options))
        .thenApply(this::createCompleteOperationResponse)
        .exceptionally(
            ex -> {
              if (ex.getCause() instanceof StatusRuntimeException) {
                throw NexusUtil.grpcExceptionToHandlerException(
                    (StatusRuntimeException) ex.getCause());
              } else if (ex instanceof RuntimeException) {
                throw (RuntimeException) ex;
              } else {
                throw new CompletionException(ex);
              }
            });
  }

  <T> T callClientMethod(Callable<T> c) {
    try {
      return c.call();
    } catch (StatusRuntimeException sre) {
      Status status = sre.getStatus();
      switch (status.getCode()) {
        case INVALID_ARGUMENT:
          throw new HandlerException(HandlerException.ErrorType.BAD_REQUEST, sre);
        case ALREADY_EXISTS:
        case FAILED_PRECONDITION:
        case OUT_OF_RANGE:
          throw new HandlerException(
              HandlerException.ErrorType.INTERNAL,
              sre,
              HandlerException.RetryBehavior.NON_RETRYABLE);
        case ABORTED:
        case UNAVAILABLE:
          throw new HandlerException(HandlerException.ErrorType.UNAVAILABLE, sre);
        case CANCELLED:
        case DATA_LOSS:
        case INTERNAL:
        case UNKNOWN:
        case UNAUTHENTICATED:
        case PERMISSION_DENIED:
          throw new HandlerException(HandlerException.ErrorType.INTERNAL, sre);
        case NOT_FOUND:
          throw new HandlerException(HandlerException.ErrorType.NOT_FOUND, sre);
        case RESOURCE_EXHAUSTED:
          throw new HandlerException(HandlerException.ErrorType.RESOURCE_EXHAUSTED, sre);
        case UNIMPLEMENTED:
          throw new HandlerException(HandlerException.ErrorType.NOT_IMPLEMENTED, sre);
        case DEADLINE_EXCEEDED:
          throw new HandlerException(HandlerException.ErrorType.UPSTREAM_TIMEOUT, sre);
        default:
          throw new HandlerException(
              HandlerException.ErrorType.INTERNAL,
              new IllegalStateException("Unexpected gRPC status code: " + status.getCode(), sre));
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
