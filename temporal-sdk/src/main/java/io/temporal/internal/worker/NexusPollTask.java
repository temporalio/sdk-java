package io.temporal.internal.worker;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.protobuf.Timestamp;
import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.WorkerVersionCapabilities;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.MetricsType;
import io.temporal.worker.PollerTypeMetricsTag;
import io.temporal.worker.tuning.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class NexusPollTask implements MultiThreadedPoller.PollTask<NexusTask> {
  private static final Logger log = LoggerFactory.getLogger(NexusPollTask.class);

  private final WorkflowServiceStubs service;
  private final TrackingSlotSupplier<NexusSlotInfo> slotSupplier;
  private final Scope metricsScope;
  private final PollNexusTaskQueueRequest pollRequest;
  private final AtomicInteger pollGauge = new AtomicInteger();

  @SuppressWarnings("deprecation")
  public NexusPollTask(
      @Nonnull WorkflowServiceStubs service,
      @Nonnull String namespace,
      @Nonnull String taskQueue,
      @Nonnull String identity,
      @Nonnull WorkerVersioningOptions versioningOptions,
      @Nonnull TrackingSlotSupplier<NexusSlotInfo> slotSupplier,
      @Nonnull Scope metricsScope,
      @Nonnull Supplier<GetSystemInfoResponse.Capabilities> serverCapabilities) {
    this.service = Objects.requireNonNull(service);
    this.slotSupplier = slotSupplier;
    this.metricsScope = Objects.requireNonNull(metricsScope);

    PollNexusTaskQueueRequest.Builder pollRequest =
        PollNexusTaskQueueRequest.newBuilder()
            .setNamespace(namespace)
            .setIdentity(identity)
            .setTaskQueue(TaskQueue.newBuilder().setName(taskQueue));

    if (versioningOptions.getWorkerDeploymentOptions() != null) {
      pollRequest.setDeploymentOptions(
          WorkerVersioningProtoUtils.deploymentOptionsToProto(
              versioningOptions.getWorkerDeploymentOptions()));
    } else if (serverCapabilities.get().getBuildIdBasedVersioning()) {
      pollRequest.setWorkerVersionCapabilities(
          WorkerVersionCapabilities.newBuilder()
              .setBuildId(versioningOptions.getBuildId())
              .setUseVersioning(versioningOptions.isUsingVersioning())
              .build());
    }
    this.pollRequest = pollRequest.build();
  }

  @Override
  @SuppressWarnings("deprecation")
  public NexusTask poll() {
    if (log.isTraceEnabled()) {
      log.trace("poll request begin: " + pollRequest);
    }
    PollNexusTaskQueueResponse response;
    SlotPermit permit;
    SlotSupplierFuture future;
    boolean isSuccessful = false;
    try {
      future =
          slotSupplier.reserveSlot(
              new SlotReservationData(
                  pollRequest.getTaskQueue().getName(),
                  pollRequest.getIdentity(),
                  pollRequest.getWorkerVersionCapabilities().getBuildId()));
    } catch (Exception e) {
      log.warn("Error while trying to reserve a slot for a nexus task", e.getCause());
      return null;
    }
    permit = MultiThreadedPoller.getSlotPermitAndHandleInterrupts(future, slotSupplier);
    if (permit == null) return null;

    MetricsTag.tagged(metricsScope, PollerTypeMetricsTag.PollerType.NEXUS_TASK)
        .gauge(MetricsType.NUM_POLLERS)
        .update(pollGauge.incrementAndGet());

    try {
      response =
          service
              .blockingStub()
              .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
              .pollNexusTaskQueue(pollRequest);

      if (response == null || response.getTaskToken().isEmpty()) {
        metricsScope.counter(MetricsType.NEXUS_POLL_NO_TASK_COUNTER).inc(1);
        return null;
      }

      Timestamp startedTime = ProtobufTimeUtils.getCurrentProtoTime();
      metricsScope
          .timer(MetricsType.NEXUS_SCHEDULE_TO_START_LATENCY)
          .record(
              ProtobufTimeUtils.toM3Duration(
                  startedTime, response.getRequest().getScheduledTime()));

      isSuccessful = true;
      return new NexusTask(
          response,
          permit,
          () -> slotSupplier.releaseSlot(SlotReleaseReason.taskComplete(), permit));
    } finally {
      MetricsTag.tagged(metricsScope, PollerTypeMetricsTag.PollerType.NEXUS_TASK)
          .gauge(MetricsType.NUM_POLLERS)
          .update(pollGauge.decrementAndGet());

      if (!isSuccessful) slotSupplier.releaseSlot(SlotReleaseReason.neverUsed(), permit);
    }
  }
}
