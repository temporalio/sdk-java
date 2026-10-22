package io.temporal.internal.worker;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.protobuf.DoubleValue;
import com.uber.m3.tally.Scope;
import io.grpc.Context;
import io.temporal.api.common.v1.WorkerVersionCapabilities;
import io.temporal.api.taskqueue.v1.PollerScalingInfo;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.taskqueue.v1.TaskQueueMetadata;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.internal.common.GrpcUtils;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.MetricsType;
import io.temporal.worker.PollerTypeMetricsTag;
import io.temporal.worker.tuning.ActivitySlotInfo;
import io.temporal.worker.tuning.SlotPermit;
import io.temporal.worker.tuning.SlotReleaseReason;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AsyncActivityPollTask implements AsyncPoller.PollTaskAsync<ActivityTask> {
  private static final Logger log = LoggerFactory.getLogger(AsyncActivityPollTask.class);

  private final TrackingSlotSupplier<?> slotSupplier;
  private final WorkflowServiceStubs service;
  private final Scope metricsScope;
  private final PollActivityTaskQueueRequest pollRequest;
  private final Context.CancellableContext grpcContext = Context.ROOT.withCancellation();
  private final PollerTracker pollerTracker;

  @SuppressWarnings("deprecation")
  public AsyncActivityPollTask(
      @Nonnull WorkflowServiceStubs service,
      @Nonnull String namespace,
      @Nonnull String taskQueue,
      @Nonnull String identity,
      @Nonnull String workerInstanceKey,
      @Nonnull WorkerVersioningOptions versioningOptions,
      double activitiesPerSecond,
      @Nonnull TrackingSlotSupplier<ActivitySlotInfo> slotSupplier,
      @Nonnull Scope metricsScope,
      @Nonnull Supplier<GetSystemInfoResponse.Capabilities> serverCapabilities,
      @Nonnull PollerTracker pollerTracker,
      String workerControlTaskQueue) {
    this.service = service;
    this.slotSupplier = slotSupplier;
    this.metricsScope = metricsScope;
    this.pollerTracker = Objects.requireNonNull(pollerTracker);

    PollActivityTaskQueueRequest.Builder pollRequest =
        PollActivityTaskQueueRequest.newBuilder()
            .setNamespace(namespace)
            .setIdentity(identity)
            .setTaskQueue(TaskQueue.newBuilder().setName(taskQueue));
    pollRequest.setWorkerInstanceKey(workerInstanceKey);
    if (workerControlTaskQueue != null) {
      pollRequest.setWorkerControlTaskQueue(workerControlTaskQueue);
    }
    if (activitiesPerSecond > 0) {
      pollRequest.setTaskQueueMetadata(
          TaskQueueMetadata.newBuilder()
              .setMaxTasksPerSecond(DoubleValue.newBuilder().setValue(activitiesPerSecond).build())
              .build());
    }

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

  /**
   * Supplies the poller pool's current scaling target. Installed by AsyncPoller, which owns the
   * scale report handle; null until then, and for a pool that is not autoscaling.
   */
  private volatile IntSupplier pollerTargetSupplier;

  @Override
  public void setPollerTargetSupplier(IntSupplier pollerTargetSupplier) {
    this.pollerTargetSupplier = pollerTargetSupplier;
    // Also hand it to the tracker, which is what the heartbeat builder can reach.
    pollerTracker.setPollerTargetSupplier(pollerTargetSupplier);
  }

  /**
   * The poll request with the pool's current scaling target attached, so the server can spread
   * scaling suggestions across a fleet by share rather than by whoever receives the most tasks.
   * Rebuilt per poll because the target moves; falls back to the prebuilt request when nothing is
   * reporting, which the server reads as "no report".
   */
  private PollActivityTaskQueueRequest pollRequestWithScalingInfo() {
    IntSupplier supplier = pollerTargetSupplier;
    if (supplier == null) {
      return pollRequest;
    }
    return pollRequest.toBuilder()
        .setPollerScalingInfo(
            PollerScalingInfo.newBuilder().setPollerTarget(supplier.getAsInt()).build())
        .build();
  }

  @Override
  public CompletableFuture<ActivityTask> poll(SlotPermit permit) {
    PollActivityTaskQueueRequest request = pollRequestWithScalingInfo();
    if (log.isTraceEnabled()) {
      log.trace("poll request begin: " + request);
    }

    MetricsTag.tagged(metricsScope, PollerTypeMetricsTag.PollerType.ACTIVITY_TASK)
        .gauge(MetricsType.NUM_POLLERS)
        .update(pollerTracker.pollStarted());

    CompletableFuture<PollActivityTaskQueueResponse> response = null;
    try {
      response =
          grpcContext.call(
              () ->
                  GrpcUtils.toCompletableFuture(
                      service
                          .futureStub()
                          .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                          .pollActivityTaskQueue(request)));
    } catch (Exception e) {
      MetricsTag.tagged(metricsScope, PollerTypeMetricsTag.PollerType.ACTIVITY_TASK)
          .gauge(MetricsType.NUM_POLLERS)
          .update(pollerTracker.pollCompleted());
      throw new RuntimeException(e);
    }

    return response
        .thenApply(
            r -> {
              if (r == null || r.getTaskToken().isEmpty()) {
                metricsScope.counter(MetricsType.ACTIVITY_POLL_NO_TASK_COUNTER).inc(1);
                return null;
              }
              pollerTracker.pollSucceeded();
              metricsScope
                  .timer(MetricsType.ACTIVITY_SCHEDULE_TO_START_LATENCY)
                  .record(
                      ProtobufTimeUtils.toM3Duration(
                          r.getStartedTime(), r.getCurrentAttemptScheduledTime()));
              return new ActivityTask(
                  r,
                  permit,
                  () -> slotSupplier.releaseSlot(SlotReleaseReason.taskComplete(), permit));
            })
        .whenComplete(
            (r, e) -> {
              MetricsTag.tagged(metricsScope, PollerTypeMetricsTag.PollerType.ACTIVITY_TASK)
                  .gauge(MetricsType.NUM_POLLERS)
                  .update(pollerTracker.pollCompleted());
            });
  }

  @Override
  public void cancel(Throwable cause) {
    grpcContext.cancel(cause);
  }

  @Override
  public String getLabel() {
    return "AsyncActivityPollTask";
  }

  @Override
  public String toString() {
    return "AsyncActivityPollTask{}";
  }
}
