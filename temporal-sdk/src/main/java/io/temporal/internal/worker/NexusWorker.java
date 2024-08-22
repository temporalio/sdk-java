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

package io.temporal.internal.worker;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.Duration;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.nexus.v1.HandlerError;
import io.temporal.api.nexus.v1.Request;
import io.temporal.api.nexus.v1.Response;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.logging.LoggerTag;
import io.temporal.internal.retryer.GrpcRetryer;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.rpcretry.DefaultStubServiceOperationRpcRetryOptions;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkerMetricsTag;
import io.temporal.worker.tuning.*;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

final class NexusWorker implements SuspendableWorker {
  private static final Logger log = LoggerFactory.getLogger(NexusWorker.class);

  private SuspendableWorker poller = new NoopWorker();
  private PollTaskExecutor<NexusTask> pollTaskExecutor;

  private final NexusTaskHandler handler;
  private final WorkflowServiceStubs service;
  private final String namespace;
  private final String taskQueue;
  private final SingleWorkerOptions options;
  private final PollerOptions pollerOptions;
  private final Scope workerMetricsScope;
  private final GrpcRetryer grpcRetryer;
  private final GrpcRetryer.GrpcRetryerOptions replyGrpcRetryerOptions;
  private final TrackingSlotSupplier<NexusSlotInfo> slotSupplier;

  public NexusWorker(
      @Nonnull WorkflowServiceStubs service,
      @Nonnull String namespace,
      @Nonnull String taskQueue,
      @Nonnull SingleWorkerOptions options,
      @Nonnull NexusTaskHandler handler,
      @Nonnull SlotSupplier<NexusSlotInfo> slotSupplier) {
    this.service = Objects.requireNonNull(service);
    this.namespace = Objects.requireNonNull(namespace);
    this.taskQueue = Objects.requireNonNull(taskQueue);
    this.handler = Objects.requireNonNull(handler);
    this.options = Objects.requireNonNull(options);
    this.pollerOptions = getPollerOptions(options);
    this.workerMetricsScope =
        MetricsTag.tagged(options.getMetricsScope(), WorkerMetricsTag.WorkerType.NEXUS_WORKER);
    this.grpcRetryer = new GrpcRetryer(service.getServerCapabilities());
    this.replyGrpcRetryerOptions =
        new GrpcRetryer.GrpcRetryerOptions(
            DefaultStubServiceOperationRpcRetryOptions.INSTANCE, null);

    this.slotSupplier = new TrackingSlotSupplier<>(slotSupplier, this.workerMetricsScope);
  }

  @Override
  public boolean start() {
    if (handler.start()) {
      this.pollTaskExecutor =
          new PollTaskExecutor<>(
              namespace,
              taskQueue,
              options.getIdentity(),
              new TaskHandlerImpl(handler),
              pollerOptions,
              slotSupplier.maximumSlots().orElse(Integer.MAX_VALUE),
              true);
      poller =
          new Poller<>(
              options.getIdentity(),
              new NexusPollTask(
                  service,
                  namespace,
                  taskQueue,
                  options.getIdentity(),
                  options.getBuildId(),
                  options.isUsingBuildIdForVersioning(),
                  this.slotSupplier,
                  workerMetricsScope,
                  service.getServerCapabilities()),
              this.pollTaskExecutor,
              pollerOptions,
              workerMetricsScope);
      poller.start();
      workerMetricsScope.counter(MetricsType.WORKER_START_COUNTER).inc(1);
      return true;
    } else {
      return false;
    }
  }

  @Override
  public CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptTasks) {
    String supplierName = this + "#executorSlots";
    return poller
        .shutdown(shutdownManager, interruptTasks)
        .thenCompose(
            ignore ->
                !interruptTasks
                    ? shutdownManager.waitForSupplierPermitsReleasedUnlimited(
                        slotSupplier, supplierName)
                    : CompletableFuture.completedFuture(null))
        .thenCompose(
            ignore ->
                pollTaskExecutor != null
                    ? pollTaskExecutor.shutdown(shutdownManager, interruptTasks)
                    : CompletableFuture.completedFuture(null))
        .exceptionally(
            e -> {
              log.error("Unexpected exception during shutdown", e);
              return null;
            });
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    long timeoutMillis = ShutdownManager.awaitTermination(poller, unit.toMillis(timeout));
    ShutdownManager.awaitTermination(pollTaskExecutor, timeoutMillis);
  }

  @Override
  public void suspendPolling() {
    poller.suspendPolling();
  }

  @Override
  public void resumePolling() {
    poller.resumePolling();
  }

  @Override
  public boolean isShutdown() {
    return poller.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return poller.isTerminated() && (pollTaskExecutor == null || pollTaskExecutor.isTerminated());
  }

  @Override
  public boolean isSuspended() {
    return poller.isSuspended();
  }

  @Override
  public WorkerLifecycleState getLifecycleState() {
    return poller.getLifecycleState();
  }

  private PollerOptions getPollerOptions(SingleWorkerOptions options) {
    PollerOptions pollerOptions = options.getPollerOptions();
    if (pollerOptions.getPollThreadNamePrefix() == null) {
      pollerOptions =
          PollerOptions.newBuilder(pollerOptions)
              .setPollThreadNamePrefix(
                  WorkerThreadsNameHelper.getNexusPollerThreadPrefix(namespace, taskQueue))
              .build();
    }
    return pollerOptions;
  }

  @Override
  public String toString() {
    return String.format(
        "NexusWorker{identity=%s, namespace=%s, taskQueue=%s}",
        options.getIdentity(), namespace, taskQueue);
  }

  private class TaskHandlerImpl implements PollTaskExecutor.TaskHandler<NexusTask> {

    final NexusTaskHandler handler;

    private TaskHandlerImpl(NexusTaskHandler handler) {
      this.handler = handler;
    }

    private String getNexusTaskService(PollNexusTaskQueueResponseOrBuilder pollResponse) {
      Request request = pollResponse.getRequest();
      if (request.hasStartOperation()) {
        return request.getStartOperation().getService();
      } else if (request.hasCancelOperation()) {
        return request.getCancelOperation().getService();
      }
      return "";
    }

    private String getNexusTaskOperation(PollNexusTaskQueueResponseOrBuilder pollResponse) {
      Request request = pollResponse.getRequest();
      if (request.hasStartOperation()) {
        return request.getStartOperation().getOperation();
      } else if (request.hasCancelOperation()) {
        return request.getCancelOperation().getOperation();
      }
      return "";
    }

    @Override
    public void handle(NexusTask task) {
      PollNexusTaskQueueResponseOrBuilder pollResponse = task.getResponse();
      // Extract service and operation from the request and set them as MDC and metrics
      // scope tags. If the request does not have a service or operation, do not set the tags.
      // If we don't know how to handle the task, we will fail the task further down the line.
      Scope metricsScope = workerMetricsScope;
      String service = getNexusTaskService(pollResponse);
      if (service.isEmpty()) {
        MDC.put(LoggerTag.NEXUS_SERVICE, service);
        metricsScope = metricsScope.tagged(ImmutableMap.of(MetricsTag.NEXUS_SERVICE, service));
      }
      String operation = getNexusTaskOperation(pollResponse);
      if (operation.isEmpty()) {
        MDC.put(LoggerTag.NEXUS_OPERATION, operation);
        metricsScope = metricsScope.tagged(ImmutableMap.of(MetricsTag.NEXUS_OPERATION, operation));
      }
      slotSupplier.markSlotUsed(
          new NexusSlotInfo(
              service, operation, taskQueue, options.getIdentity(), options.getBuildId()),
          task.getPermit());

      NexusTaskHandler.Result result = null;
      try {
        result = handleNexusTask(task, metricsScope);
      } finally {
        task.getCompletionCallback().apply();
        MDC.remove(LoggerTag.NEXUS_SERVICE);
        MDC.remove(LoggerTag.NEXUS_OPERATION);
      }
    }

    @Override
    public Throwable wrapFailure(NexusTask task, Throwable failure) {
      PollNexusTaskQueueResponseOrBuilder response = task.getResponse();
      return new RuntimeException(
          "Failure processing nexus response: " + response.getRequest().toString(), failure);
    }

    private NexusTaskHandler.Result handleNexusTask(NexusTask task, Scope metricsScope) {
      PollNexusTaskQueueResponseOrBuilder pollResponse = task.getResponse();
      ByteString taskToken = pollResponse.getTaskToken();

      NexusTaskHandler.Result result;

      Stopwatch sw = metricsScope.timer(MetricsType.NEXUS_EXEC_LATENCY).start();
      try {
        result = handler.handle(task, metricsScope);
        if (result.getHandlerError() != null) {
          metricsScope.counter(MetricsType.NEXUS_EXEC_FAILED_COUNTER).inc(1);
        }
      } catch (Throwable ex) {
        // handler.handle if expected to never throw an exception and return result
        // that can be used for a workflow callback if this method throws, it's a bug.
        log.error("[BUG] Code that expected to never throw an exception threw an exception", ex);
        throw ex;
      } finally {
        sw.stop();
      }

      try {
        sendReply(taskToken, result, metricsScope);
      } catch (Exception e) {
        logExceptionDuringResultReporting(e, pollResponse, result);
        throw e;
      }

      Duration e2eDuration =
          ProtobufTimeUtils.toM3DurationSinceNow(pollResponse.getRequest().getScheduledTime());
      metricsScope.timer(MetricsType.NEXUS_TASK_E2E_LATENCY).record(e2eDuration);
      return result;
    }

    private void logExceptionDuringResultReporting(
        Exception e,
        PollNexusTaskQueueResponseOrBuilder pollResponse,
        NexusTaskHandler.Result result) {
      log.warn("Failure during reporting of nexus task result to the server", e);
    }

    private void sendReply(
        ByteString taskToken, NexusTaskHandler.Result response, Scope metricsScope) {
      Response taskResponse = response.getResponse();
      if (taskResponse != null) {
        RespondNexusTaskCompletedRequest request =
            RespondNexusTaskCompletedRequest.newBuilder()
                .setTaskToken(taskToken)
                .setIdentity(options.getIdentity())
                .setNamespace(namespace)
                .setResponse(taskResponse)
                .build();

        grpcRetryer.retry(
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .respondNexusTaskCompleted(request),
            replyGrpcRetryerOptions);
      } else {
        HandlerError taskFailed = response.getHandlerError();
        if (taskFailed != null) {
          RespondNexusTaskFailedRequest request =
              RespondNexusTaskFailedRequest.newBuilder()
                  .setTaskToken(taskToken)
                  .setIdentity(options.getIdentity())
                  .setNamespace(namespace)
                  .setError(taskFailed)
                  .build();

          grpcRetryer.retry(
              () ->
                  service
                      .blockingStub()
                      .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                      .respondNexusTaskFailed(request),
              replyGrpcRetryerOptions);
        } else {
          throw new IllegalArgumentException("[BUG] Either response or failure must be set");
        }
      }
    }
  }
}
