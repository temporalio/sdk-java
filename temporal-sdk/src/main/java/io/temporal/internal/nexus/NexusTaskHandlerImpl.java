/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.nexus;

import static io.temporal.internal.common.NexusUtil.exceptionToNexusFailure;
import static io.temporal.internal.common.NexusUtil.nexusProtoLinkToLink;

import com.uber.m3.tally.Scope;
import io.nexusrpc.Header;
import io.nexusrpc.OperationException;
import io.nexusrpc.handler.*;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.nexus.v1.*;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.failure.ApplicationFailure;
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
  private final WorkerInterceptor[] interceptors;
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
    this.interceptors = Objects.requireNonNull(interceptors);
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
    if (headers == null) {
      headers = Collections.emptyMap();
    }

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
      return new Result(
          HandlerError.newBuilder()
              .setErrorType(e.getErrorType().toString())
              .setFailure(exceptionToNexusFailure(e.getCause(), dataConverter))
              .build());
    } catch (Throwable e) {
      return new Result(
          HandlerError.newBuilder()
              .setErrorType(HandlerException.ErrorType.INTERNAL.toString())
              .setFailure(exceptionToNexusFailure(e, dataConverter))
              .build());
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
      throw new HandlerException(HandlerException.ErrorType.BAD_REQUEST, failure);
    }
    if (failure instanceof ApplicationFailure) {
      if (((ApplicationFailure) failure).isNonRetryable()) {
        throw new HandlerException(HandlerException.ErrorType.BAD_REQUEST, failure);
      }
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    throw failure instanceof RuntimeException
        ? (RuntimeException) failure
        : new RuntimeException(failure);
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

  @SuppressWarnings("deprecation") // getOperationId kept to support old server for a while
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
      startResponseBuilder.setOperationError(
          UnsuccessfulOperationError.newBuilder()
              .setOperationState(e.getState().toString().toLowerCase())
              .setFailure(exceptionToNexusFailure(e.getCause(), dataConverter))
              .build());
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
