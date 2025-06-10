package io.temporal.internal.worker;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.protobuf.Timestamp;
import com.uber.m3.tally.Scope;
import io.grpc.Context;
import io.temporal.api.common.v1.WorkerVersionCapabilities;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.PollNexusTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollNexusTaskQueueResponse;
import io.temporal.internal.common.GrpcUtils;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.MetricsType;
import io.temporal.worker.PollerTypeMetricsTag;
import io.temporal.worker.tuning.SlotPermit;
import io.temporal.worker.tuning.SlotReleaseReason;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AsyncNexusPollTask implements AsyncPoller.PollTaskAsync<NexusTask> {
  private static final Logger log = LoggerFactory.getLogger(AsyncNexusPollTask.class);

  private final TrackingSlotSupplier<?> slotSupplier;
  private final WorkflowServiceStubs service;
  private final Scope metricsScope;
  private final PollNexusTaskQueueRequest pollRequest;
  private final AtomicInteger pollGauge = new AtomicInteger();
  private final Context.CancellableContext grpcContext = Context.ROOT.withCancellation();

  @SuppressWarnings("deprecation")
  public AsyncNexusPollTask(
      @Nonnull WorkflowServiceStubs service,
      @Nonnull String namespace,
      @Nonnull String taskQueue,
      @Nonnull String identity,
      @Nullable String buildId,
      boolean useBuildIdForVersioning,
      @Nonnull Scope metricsScope,
      @Nonnull Supplier<GetSystemInfoResponse.Capabilities> serverCapabilities,
      TrackingSlotSupplier<?> slotSupplier) {
    this.service = Objects.requireNonNull(service);
    this.metricsScope = Objects.requireNonNull(metricsScope);
    this.slotSupplier = slotSupplier;

    PollNexusTaskQueueRequest.Builder pollRequest =
        PollNexusTaskQueueRequest.newBuilder()
            .setNamespace(namespace)
            .setIdentity(identity)
            .setTaskQueue(TaskQueue.newBuilder().setName(taskQueue));

    if (serverCapabilities.get().getBuildIdBasedVersioning()) {
      pollRequest.setWorkerVersionCapabilities(
          WorkerVersionCapabilities.newBuilder()
              .setBuildId(buildId)
              .setUseVersioning(useBuildIdForVersioning)
              .build());
    }
    this.pollRequest = pollRequest.build();
  }

  @Override
  @SuppressWarnings("deprecation")
  public CompletableFuture<NexusTask> poll(SlotPermit permit) {
    if (log.isTraceEnabled()) {
      log.trace("poll request begin: " + pollRequest);
    }

    MetricsTag.tagged(metricsScope, PollerTypeMetricsTag.PollerType.NEXUS_TASK)
        .gauge(MetricsType.NUM_POLLERS)
        .update(pollGauge.incrementAndGet());

    CompletableFuture<PollNexusTaskQueueResponse> response = null;
    try {
      response =
          grpcContext.call(
              () ->
                  GrpcUtils.toCompletableFuture(
                      service
                          .futureStub()
                          .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                          .pollNexusTaskQueue(pollRequest)));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    return response
        .thenApply(
            r -> {
              if (r == null || r.getTaskToken().isEmpty()) {
                metricsScope.counter(MetricsType.NEXUS_POLL_NO_TASK_COUNTER).inc(1);
                return null;
              }
              Timestamp startedTime = ProtobufTimeUtils.getCurrentProtoTime();
              metricsScope
                  .timer(MetricsType.NEXUS_SCHEDULE_TO_START_LATENCY)
                  .record(
                      ProtobufTimeUtils.toM3Duration(
                          startedTime, r.getRequest().getScheduledTime()));
              return new NexusTask(
                  r,
                  permit,
                  () -> slotSupplier.releaseSlot(SlotReleaseReason.taskComplete(), permit));
            })
        .whenComplete(
            (r, e) ->
                MetricsTag.tagged(metricsScope, PollerTypeMetricsTag.PollerType.NEXUS_TASK)
                    .gauge(MetricsType.NUM_POLLERS)
                    .update(pollGauge.decrementAndGet()));
  }

  @Override
  public void cancel(Throwable cause) {
    grpcContext.cancel(cause);
  }

  @Override
  public String getLabel() {
    return "AsyncNexusPollTask";
  }

  @Override
  public String toString() {
    return "AsyncNexusPollTask{}";
  }
}
