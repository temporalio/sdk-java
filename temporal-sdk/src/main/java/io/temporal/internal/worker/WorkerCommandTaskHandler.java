package io.temporal.internal.worker;

import com.google.protobuf.InvalidProtocolBufferException;
import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.nexus.v1.Response;
import io.temporal.api.nexus.v1.StartOperationRequest;
import io.temporal.api.nexus.v1.StartOperationResponse;
import io.temporal.api.nexusservices.workerservice.v1.ExecuteCommandsRequest;
import io.temporal.api.nexusservices.workerservice.v1.ExecuteCommandsResponse;
import io.temporal.api.worker.v1.CancelActivityResult;
import io.temporal.api.worker.v1.WorkerCommand;
import io.temporal.api.worker.v1.WorkerCommandResult;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.GlobalDataConverter;
import io.temporal.serviceclient.Version;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.tuning.FixedSizeSlotSupplier;
import io.temporal.worker.tuning.NexusSlotInfo;
import io.temporal.worker.tuning.PollerBehaviorSimpleMaximum;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles server-to-worker commands delivered on the worker command Nexus task queue. */
public final class WorkerCommandTaskHandler implements NexusTaskHandler {
  private static final Logger log = LoggerFactory.getLogger(WorkerCommandTaskHandler.class);
  private static final String TASK_QUEUE_PREFIX = "temporal-sys/worker-commands";

  private final Function<byte[], Boolean> activityCancelCallback;

  public WorkerCommandTaskHandler(Function<byte[], Boolean> activityCancelCallback) {
    this.activityCancelCallback = Objects.requireNonNull(activityCancelCallback);
  }

  public static String workerControlTaskQueue(String namespace, String workerGroupingKey) {
    return String.format("%s/%s/%s", TASK_QUEUE_PREFIX, namespace, workerGroupingKey);
  }

  public static SuspendableWorker newWorkerCommandWorker(
      @Nonnull WorkflowServiceStubs service,
      @Nonnull String namespace,
      @Nonnull String identity,
      @Nonnull String workerGroupingKey,
      @Nonnull Function<byte[], Boolean> activityCancelCallback,
      @Nonnull Scope metricsScope,
      @Nonnull NamespaceCapabilities namespaceCapabilities) {
    String taskQueue = workerControlTaskQueue(namespace, workerGroupingKey);
    DataConverter dataConverter = GlobalDataConverter.get();
    SingleWorkerOptions options =
        SingleWorkerOptions.newBuilder()
            .setIdentity(identity)
            .setBuildId(Version.LIBRARY_VERSION)
            .setWorkerInstanceKey(workerGroupingKey)
            .setDataConverter(dataConverter)
            .setMetricsScope(metricsScope)
            .setPollerOptions(
                PollerOptions.newBuilder()
                    .setPollerBehavior(new PollerBehaviorSimpleMaximum(1))
                    .setPollThreadNamePrefix("WorkerCommandNexusPoller")
                    .build())
            .build();
    return new NexusWorker(
        service,
        namespace,
        taskQueue,
        options,
        new WorkerCommandTaskHandler(activityCancelCallback),
        dataConverter,
        new FixedSizeSlotSupplier<NexusSlotInfo>(5),
        namespaceCapabilities,
        true);
  }

  @Override
  public boolean start() {
    return true;
  }

  @Override
  public Result handle(NexusTask task, Scope metricsScope) throws TimeoutException {
    ExecuteCommandsRequest request = decodeRequest(task);
    ExecuteCommandsResponse.Builder response = ExecuteCommandsResponse.newBuilder();
    for (WorkerCommand command : request.getCommandsList()) {
      response.addResults(handleCommand(command));
    }
    return new Result(
        Response.newBuilder()
            .setStartOperation(
                StartOperationResponse.newBuilder()
                    .setSyncSuccess(
                        StartOperationResponse.Sync.newBuilder()
                            .setPayload(
                                Payload.newBuilder().setData(response.build().toByteString()))))
            .build());
  }

  private ExecuteCommandsRequest decodeRequest(NexusTask task) {
    StartOperationRequest request = task.getResponse().getRequest().getStartOperation();
    if (!request.hasPayload()) {
      throw new IllegalArgumentException(
          "Worker command Nexus task missing ExecuteCommands payload");
    }
    try {
      return ExecuteCommandsRequest.parseFrom(request.getPayload().getData());
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalArgumentException("Failed to decode ExecuteCommandsRequest", e);
    }
  }

  private WorkerCommandResult handleCommand(WorkerCommand command) {
    WorkerCommandResult.Builder result = WorkerCommandResult.newBuilder();
    if (command.hasCancelActivity()) {
      byte[] taskToken = command.getCancelActivity().getTaskToken().toByteArray();
      Boolean found = activityCancelCallback.apply(taskToken);
      if (!Boolean.TRUE.equals(found)) {
        log.debug("Activity task token from worker command was not found");
      }
      result.setCancelActivity(CancelActivityResult.newBuilder());
    } else {
      log.warn("Unsupported worker command");
    }
    return result.build();
  }
}
