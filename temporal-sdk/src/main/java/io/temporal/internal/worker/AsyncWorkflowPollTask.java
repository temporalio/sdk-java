package io.temporal.internal.worker;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.protobuf.Timestamp;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.grpc.Context;
import io.temporal.api.common.v1.WorkerVersionCapabilities;
import io.temporal.api.enums.v1.TaskQueueKind;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.internal.common.GrpcUtils;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.MetricsType;
import io.temporal.worker.PollerTypeMetricsTag;
import io.temporal.worker.tuning.SlotPermit;
import io.temporal.worker.tuning.WorkflowSlotInfo;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AsyncWorkflowPollTask
    implements AsyncPoller.PollTaskAsync<WorkflowTask>, DisableNormalPolling {
  private static final Logger log = LoggerFactory.getLogger(AsyncWorkflowPollTask.class);
  private final TrackingSlotSupplier<?> slotSupplier;
  private final WorkflowServiceStubs service;
  private final Scope metricsScope;
  private final Scope pollerMetricScope;
  private final PollWorkflowTaskQueueRequest pollRequest;
  private final AtomicInteger pollGauge = new AtomicInteger();
  private final MetricsTag.TagValue taskQueueTagValue;
  private final boolean stickyPoller;
  private final Context.CancellableContext grpcContext = Context.ROOT.withCancellation();
  private final AtomicBoolean shutdown = new AtomicBoolean(false);

  @Override
  public String toString() {
    return "AsyncWorkflowPollTask{" + "stickyPoller=" + stickyPoller + '}';
  }

  @SuppressWarnings("deprecation")
  public AsyncWorkflowPollTask(
      @Nonnull WorkflowServiceStubs service,
      @Nonnull String namespace,
      @Nonnull String taskQueue,
      @Nullable String stickyTaskQueue,
      @Nonnull String identity,
      @Nonnull WorkerVersioningOptions versioningOptions,
      @Nonnull TrackingSlotSupplier<WorkflowSlotInfo> slotSupplier,
      @Nonnull Scope metricsScope,
      @Nonnull Supplier<GetSystemInfoResponse.Capabilities> serverCapabilities) {
    this.service = service;
    this.slotSupplier = slotSupplier;
    this.metricsScope = metricsScope;

    PollWorkflowTaskQueueRequest.Builder pollRequestBuilder =
        PollWorkflowTaskQueueRequest.newBuilder()
            .setNamespace(Objects.requireNonNull(namespace))
            .setIdentity(Objects.requireNonNull(identity));

    if (versioningOptions.getWorkerDeploymentOptions() != null) {
      pollRequestBuilder.setDeploymentOptions(
          WorkerVersioningProtoUtils.deploymentOptionsToProto(
              versioningOptions.getWorkerDeploymentOptions()));
    } else if (serverCapabilities.get().getBuildIdBasedVersioning()) {
      pollRequestBuilder.setWorkerVersionCapabilities(
          WorkerVersionCapabilities.newBuilder()
              .setBuildId(versioningOptions.getBuildId())
              .setUseVersioning(versioningOptions.isUsingVersioning())
              .build());
    } else {
      pollRequestBuilder.setBinaryChecksum(versioningOptions.getBuildId());
    }
    stickyPoller = stickyTaskQueue != null && !stickyTaskQueue.isEmpty();
    if (!stickyPoller) {
      taskQueueTagValue = PollerTypeMetricsTag.PollerType.WORKFLOW_TASK;
      this.pollRequest =
          pollRequestBuilder
              .setTaskQueue(
                  TaskQueue.newBuilder()
                      .setName(taskQueue)
                      .setKind(TaskQueueKind.TASK_QUEUE_KIND_NORMAL)
                      .build())
              .build();
      this.pollerMetricScope =
          metricsScope.tagged(
              new ImmutableMap.Builder<String, String>(1)
                  .put(MetricsTag.TASK_QUEUE, String.format("%s:%s", taskQueue, "sticky"))
                  .build());
    } else {
      taskQueueTagValue = PollerTypeMetricsTag.PollerType.WORKFLOW_STICKY_TASK;
      this.pollRequest =
          pollRequestBuilder
              .setTaskQueue(
                  TaskQueue.newBuilder()
                      .setName(stickyTaskQueue)
                      .setKind(TaskQueueKind.TASK_QUEUE_KIND_STICKY)
                      .setNormalName(taskQueue)
                      .build())
              .build();
      this.pollerMetricScope = metricsScope;
    }
  }

  @Override
  public CompletableFuture<WorkflowTask> poll(SlotPermit permit)
      throws AsyncPoller.PollTaskAsyncAbort {
    if (shutdown.get()) {
      throw new AsyncPoller.PollTaskAsyncAbort("Normal poller is disabled");
    }
    if (log.isTraceEnabled()) {
      log.trace("poll request begin: " + pollRequest);
    }

    MetricsTag.tagged(metricsScope, taskQueueTagValue)
        .gauge(MetricsType.NUM_POLLERS)
        .update(pollGauge.incrementAndGet());

    CompletableFuture<PollWorkflowTaskQueueResponse> response = null;
    try {
      response =
          grpcContext.call(
              () ->
                  GrpcUtils.toCompletableFuture(
                      service
                          .futureStub()
                          .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                          .pollWorkflowTaskQueue(pollRequest)));
    } catch (Exception e) {
      MetricsTag.tagged(metricsScope, taskQueueTagValue)
          .gauge(MetricsType.NUM_POLLERS)
          .update(pollGauge.decrementAndGet());
      throw new RuntimeException(e);
    }

    return response
        .thenApply(
            r -> {
              if (r == null || r.getTaskToken().isEmpty()) {
                pollerMetricScope
                    .counter(MetricsType.WORKFLOW_TASK_QUEUE_POLL_EMPTY_COUNTER)
                    .inc(1);
                return null;
              }
              pollerMetricScope
                  .counter(MetricsType.WORKFLOW_TASK_QUEUE_POLL_SUCCEED_COUNTER)
                  .inc(1);
              pollerMetricScope
                  .timer(MetricsType.WORKFLOW_TASK_SCHEDULE_TO_START_LATENCY)
                  .record(ProtobufTimeUtils.toM3Duration(r.getStartedTime(), r.getScheduledTime()));
              return new WorkflowTask(r, (reason) -> slotSupplier.releaseSlot(reason, permit));
            })
        .whenComplete(
            (r, e) ->
                MetricsTag.tagged(metricsScope, taskQueueTagValue)
                    .gauge(MetricsType.NUM_POLLERS)
                    .update(pollGauge.decrementAndGet()));
  }

  @Override
  public void cancel(Throwable cause) {
    grpcContext.cancel(cause);
  }

  @Override
  public void disableNormalPoll() {
    if (stickyPoller) {
      throw new IllegalStateException("Cannot disable normal poll for sticky poller");
    }
    shutdown.set(true);
  }

  @Override
  public String getLabel() {
    return stickyPoller ? "StickyWorkflowPollTask" : "NormalWorkflowPollTask";
  }
}
