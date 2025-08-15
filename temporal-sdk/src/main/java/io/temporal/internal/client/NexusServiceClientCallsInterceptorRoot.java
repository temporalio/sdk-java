package io.temporal.internal.client;

import static io.temporal.internal.common.NexusFailureUtil.nexusFailureToAPIFailure;
import static io.temporal.internal.common.NexusUtil.exceptionToNexusFailure;

import com.google.common.base.Strings;
import io.grpc.StatusRuntimeException;
import io.nexusrpc.*;
import io.nexusrpc.client.transport.*;
import io.temporal.api.common.v1.Callback;
import io.temporal.api.nexus.v1.HandlerError;
import io.temporal.api.nexus.v1.TaskDispatchTarget;
import io.temporal.api.nexus.v1.UnsuccessfulOperationError;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.TemporalNexusServiceClientOptions;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.interceptors.NexusServiceClientCallsInterceptor;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.common.NexusFailureUtil;
import io.temporal.internal.common.NexusUtil;
import io.temporal.internal.common.ProtobufTimeUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class NexusServiceClientCallsInterceptorRoot
    implements NexusServiceClientCallsInterceptor {
  private final GenericWorkflowClient client;
  private final WorkflowClientOptions clientOptions;
  private final TaskDispatchTarget dispatchTarget;

  public NexusServiceClientCallsInterceptorRoot(
      GenericWorkflowClient client,
      WorkflowClientOptions clientOptions,
      TemporalNexusServiceClientOptions serviceClientOptions) {
    this.client = client;
    this.clientOptions = clientOptions;
    this.dispatchTarget =
        TaskDispatchTarget.newBuilder().setEndpoint(serviceClientOptions.getEndpoint()).build();
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
              .failureToException(
                  NexusFailureUtil.nexusFailureToAPIFailure(error.getFailure(), false));
      if (error.getOperationState().equals("canceled")) {
        throw OperationException.canceled(cause);
      } else {
        throw OperationException.failure(cause);
      }
    } else if (response.hasHandlerError()) {
      HandlerError error = response.getHandlerError();
      throw clientOptions
          .getDataConverter()
          .failureToException(NexusFailureUtil.handlerErrorToFailure(error));
    } else {
      throw new IllegalStateException("Unknown response from startNexusCall: " + response);
    }
  }

  @Override
  public StartOperationOutput startOperation(StartOperationInput input) throws OperationException {
    try {
      StartNexusOperationResponse response =
          client.startNexusOperation(
              createStartOperationRequest(
                  input.getOperationName(),
                  input.getServiceName(),
                  input.getInput(),
                  input.getOptions()));
      return new StartOperationOutput(createStartOperationResponse(response));
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
      throw clientOptions
          .getDataConverter()
          .failureToException(NexusFailureUtil.handlerErrorToFailure(error));
    } else if (response.hasStillRunning()) {
      throw new OperationStillRunningException();
    } else {
      throw new IllegalStateException("Unknown response from startNexusCall: " + response);
    }
  }

  @Override
  public FetchOperationResultOutput fetchOperationResult(FetchOperationResultInput input)
      throws OperationException, OperationStillRunningException {
    Instant startTime = Instant.now();
    while (true) {
      try {
        try {
          GetNexusOperationResultResponse response =
              client.getNexusOperationResult(
                  createGetNexusOperationResultRequest(
                      input.getOperationName(),
                      input.getServiceName(),
                      input.getOperationToken(),
                      input.getOptions()));
          return new FetchOperationResultOutput(createGetOperationResultResponse(response));
        } catch (StatusRuntimeException sre) {
          throw NexusUtil.grpcExceptionToHandlerException(sre);
        }
      } catch (OperationStillRunningException e) {
        // If the operation is still running, we wait for the specified timeout before retrying.
        if (Instant.now().isAfter(startTime.plus(input.getOptions().getTimeout()))) {
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
      throw clientOptions
          .getDataConverter()
          .failureToException(NexusFailureUtil.handlerErrorToFailure(error));
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
  public FetchOperationInfoOutput fetchOperationInfo(FetchOperationInfoInput input) {
    try {
      return new FetchOperationInfoOutput(
          createGetOperationInfoResponse(
              client.getNexusOperationInfo(
                  createGetNexusOperationInfoRequest(
                      input.getOperationName(),
                      input.getServiceName(),
                      input.getOperationToken(),
                      input.getOptions()))));
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
      throw clientOptions
          .getDataConverter()
          .failureToException(NexusFailureUtil.handlerErrorToFailure(error));
    }

    return new CancelOperationResponse();
  }

  @Override
  public CancelOperationOutput cancelOperation(CancelOperationInput input) {
    try {
      return new CancelOperationOutput(
          createRequestCancelNexusOperationResponse(
              client.requestCancelNexusOperation(
                  createRequestCancelNexusOperationRequest(
                      input.getOperationName(),
                      input.getServiceName(),
                      input.getOperationToken(),
                      input.getOptions()))));
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
      throw clientOptions
          .getDataConverter()
          .failureToException(NexusFailureUtil.handlerErrorToFailure(error));
    }

    return new CompleteOperationResponse();
  }

  @Override
  public CompleteOperationOutput completeOperation(CompleteOperationInput input) {
    try {
      return new CompleteOperationOutput(
          createCompleteOperationResponse(
              client.completeNexusOperation(
                  createCompleteNexusOperationRequest(input.getUrl(), input.getOptions()))));
    } catch (StatusRuntimeException sre) {
      throw NexusUtil.grpcExceptionToHandlerException(sre);
    }
  }

  @Override
  public CompletableFuture<StartOperationOutput> startOperationAsync(StartOperationInput input) {
    return client
        .startNexusOperationAsync(
            createStartOperationRequest(
                input.getOperationName(),
                input.getServiceName(),
                input.getInput(),
                input.getOptions()))
        .thenApply(
            response -> {
              try {
                return createStartOperationResponse(response);
              } catch (OperationException e) {
                throw new CompletionException(e);
              }
            })
        .thenApply(StartOperationOutput::new)
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
  public CompletableFuture<FetchOperationResultOutput> fetchOperationResultAsync(
      FetchOperationResultInput input) {
    Instant startTime = Instant.now();
    GetNexusOperationResultRequest request =
        createGetNexusOperationResultRequest(
            input.getOperationName(),
            input.getServiceName(),
            input.getOperationToken(),
            input.getOptions());
    CompletableFuture<GetNexusOperationResultResponse> response =
        client.getNexusOperationResultAsync(request);
    return response
        .thenComposeAsync(
            r -> waitForResult(startTime, input.getOptions().getTimeout(), request, r))
        .thenApply(FetchOperationResultOutput::new)
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
  public CompletableFuture<FetchOperationInfoOutput> fetchOperationInfoAsync(
      FetchOperationInfoInput input) {
    return client
        .getNexusOperationInfoAsync(
            createGetNexusOperationInfoRequest(
                input.getOperationName(),
                input.getServiceName(),
                input.getOperationToken(),
                input.getOptions()))
        .thenApply(this::createGetOperationInfoResponse)
        .thenApply(FetchOperationInfoOutput::new)
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
  public CompletableFuture<CancelOperationOutput> cancelOperationAsync(CancelOperationInput input) {
    return client
        .requestCancelNexusOperationAsync(
            createRequestCancelNexusOperationRequest(
                input.getOperationName(),
                input.getServiceName(),
                input.getOperationToken(),
                input.getOptions()))
        .thenApply(this::createRequestCancelNexusOperationResponse)
        .thenApply(CancelOperationOutput::new)
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
  public CompletableFuture<CompleteOperationOutput> completeOperationAsync(
      CompleteOperationAsyncInput input) {
    return client
        .completeNexusOperationAsync(
            createCompleteNexusOperationRequest(input.getUrl(), input.getOptions()))
        .thenApply(this::createCompleteOperationResponse)
        .thenApply(CompleteOperationOutput::new)
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
}
