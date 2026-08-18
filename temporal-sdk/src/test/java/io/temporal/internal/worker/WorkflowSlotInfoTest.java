package io.temporal.internal.worker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import io.temporal.api.common.v1.WorkerVersionCapabilities;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.TaskQueueKind;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.tuning.SlotMarkUsedContext;
import io.temporal.worker.tuning.SlotPermit;
import io.temporal.worker.tuning.SlotReleaseContext;
import io.temporal.worker.tuning.SlotSupplier;
import io.temporal.worker.tuning.SlotSupplierFuture;
import io.temporal.worker.tuning.WorkflowSlotInfo;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

@SuppressWarnings("deprecation")
public class WorkflowSlotInfoTest {
  private static final String WORKFLOW_TYPE = "test-workflow-type";
  private static final String TASK_QUEUE = "test-task-queue";
  private static final String STICKY_TASK_QUEUE = "test-sticky-task-queue";
  private static final String WORKFLOW_ID = "test-workflow-id";
  private static final String RUN_ID = "test-run-id";
  private static final String WORKER_IDENTITY = "test-worker-identity";
  private static final String WORKER_BUILD_ID = "test-worker-build-id";

  @Test
  public void normalWorkflowSlotInfoHasExpectedFields() {
    PollWorkflowTaskQueueRequest request =
        PollWorkflowTaskQueueRequest.newBuilder()
            .setIdentity(WORKER_IDENTITY)
            .setTaskQueue(
                TaskQueue.newBuilder()
                    .setName(TASK_QUEUE)
                    .setKind(TaskQueueKind.TASK_QUEUE_KIND_NORMAL))
            .setWorkerVersionCapabilities(
                WorkerVersionCapabilities.newBuilder().setBuildId(WORKER_BUILD_ID))
            .build();

    WorkflowSlotInfo slotInfo = new WorkflowSlotInfo(workflowResponse(), request);

    assertWorkflowSlotInfo(slotInfo, false);
  }

  @Test
  public void deploymentBuildIdIsIncludedInWorkflowSlotInfo() {
    PollWorkflowTaskQueueRequest request =
        normalPollRequestBuilder()
            .setDeploymentOptions(
                io.temporal.api.deployment.v1.WorkerDeploymentOptions.newBuilder()
                    .setBuildId(WORKER_BUILD_ID))
            .build();

    WorkflowSlotInfo slotInfo = new WorkflowSlotInfo(workflowResponse(), request);

    assertEquals(WORKER_BUILD_ID, slotInfo.getWorkerBuildId());
  }

  @Test
  public void binaryChecksumIsIncludedInWorkflowSlotInfo() {
    PollWorkflowTaskQueueRequest request =
        normalPollRequestBuilder().setBinaryChecksum(WORKER_BUILD_ID).build();

    WorkflowSlotInfo slotInfo = new WorkflowSlotInfo(workflowResponse(), request);

    assertEquals(WORKER_BUILD_ID, slotInfo.getWorkerBuildId());
  }

  @Test
  public void synchronousStickyPollUsesSelectedRequestForSlotInfo() {
    WorkflowServiceStubs client = mock(WorkflowServiceStubs.class);
    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(client.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);
    when(blockingStub.pollWorkflowTaskQueue(any())).thenReturn(workflowResponse());

    RecordingSlotSupplier recordingSupplier = new RecordingSlotSupplier();
    TrackingSlotSupplier<WorkflowSlotInfo> trackingSupplier =
        new TrackingSlotSupplier<>(recordingSupplier, new NoopScope());
    WorkflowPollTask pollTask =
        new WorkflowPollTask(
            client,
            "default",
            TASK_QUEUE,
            STICKY_TASK_QUEUE,
            WORKER_IDENTITY,
            "test-instance-key",
            new WorkerVersioningOptions(WORKER_BUILD_ID, false, null),
            trackingSupplier,
            new StickyQueueBalancer(1, true),
            new NoopScope(),
            WorkflowSlotInfoTest::buildIdCapabilities,
            new PollerTracker(),
            new PollerTracker(),
            null);

    WorkflowTask task = pollTask.poll();

    assertNotNull(task);
    assertNotNull(recordingSupplier.markUsedContext);
    assertSame(recordingSupplier.reservedPermit, recordingSupplier.markUsedContext.getSlotPermit());
    assertWorkflowSlotInfo(recordingSupplier.markUsedContext.getSlotInfo(), true);

    task.getCompletionCallback().apply(io.temporal.worker.tuning.SlotReleaseReason.taskComplete());
    assertNotNull(recordingSupplier.releaseContext);
    assertSame(
        recordingSupplier.markUsedContext.getSlotInfo(),
        recordingSupplier.releaseContext.getSlotInfo());
  }

  @Test
  public void asynchronousPollsIncludeNormalAndStickyQueueFields() throws Exception {
    assertAsyncWorkflowSlotInfo(null, false);
    assertAsyncWorkflowSlotInfo(STICKY_TASK_QUEUE, true);
  }

  private static void assertAsyncWorkflowSlotInfo(String stickyTaskQueue, boolean expectedSticky)
      throws Exception {
    WorkflowServiceStubs client = mock(WorkflowServiceStubs.class);
    WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub =
        mock(WorkflowServiceGrpc.WorkflowServiceFutureStub.class);
    when(client.futureStub()).thenReturn(futureStub);
    when(futureStub.withOption(any(), any())).thenReturn(futureStub);
    when(futureStub.pollWorkflowTaskQueue(any()))
        .thenReturn(Futures.immediateFuture(workflowResponse()));

    RecordingSlotSupplier recordingSupplier = new RecordingSlotSupplier();
    TrackingSlotSupplier<WorkflowSlotInfo> trackingSupplier =
        new TrackingSlotSupplier<>(recordingSupplier, new NoopScope());
    AsyncWorkflowPollTask pollTask =
        new AsyncWorkflowPollTask(
            client,
            "default",
            TASK_QUEUE,
            stickyTaskQueue,
            WORKER_IDENTITY,
            "test-instance-key",
            new WorkerVersioningOptions(WORKER_BUILD_ID, false, null),
            trackingSupplier,
            new NoopScope(),
            WorkflowSlotInfoTest::buildIdCapabilities,
            new PollerTracker(),
            null);
    SlotPermit permit = new SlotPermit();

    CompletableFuture<WorkflowTask> future = pollTask.poll(permit);
    WorkflowTask task = future.get();

    assertNotNull(task);
    assertNotNull(recordingSupplier.markUsedContext);
    assertSame(permit, recordingSupplier.markUsedContext.getSlotPermit());
    assertWorkflowSlotInfo(recordingSupplier.markUsedContext.getSlotInfo(), expectedSticky);
  }

  private static PollWorkflowTaskQueueRequest.Builder normalPollRequestBuilder() {
    return PollWorkflowTaskQueueRequest.newBuilder()
        .setIdentity(WORKER_IDENTITY)
        .setTaskQueue(
            TaskQueue.newBuilder()
                .setName(TASK_QUEUE)
                .setKind(TaskQueueKind.TASK_QUEUE_KIND_NORMAL));
  }

  private static PollWorkflowTaskQueueResponse workflowResponse() {
    return PollWorkflowTaskQueueResponse.newBuilder()
        .setTaskToken(ByteString.copyFrom("token", UTF_8))
        .setWorkflowExecution(
            WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).setRunId(RUN_ID))
        .setWorkflowType(WorkflowType.newBuilder().setName(WORKFLOW_TYPE))
        .build();
  }

  private static GetSystemInfoResponse.Capabilities buildIdCapabilities() {
    return GetSystemInfoResponse.Capabilities.newBuilder().setBuildIdBasedVersioning(true).build();
  }

  private static void assertWorkflowSlotInfo(WorkflowSlotInfo slotInfo, boolean expectedSticky) {
    assertEquals(WORKFLOW_TYPE, slotInfo.getWorkflowType());
    assertEquals(TASK_QUEUE, slotInfo.getTaskQueue());
    assertEquals(WORKFLOW_ID, slotInfo.getWorkflowId());
    assertEquals(RUN_ID, slotInfo.getRunId());
    assertEquals(WORKER_IDENTITY, slotInfo.getWorkerIdentity());
    assertEquals(WORKER_BUILD_ID, slotInfo.getWorkerBuildId());
    if (expectedSticky) {
      assertTrue(slotInfo.isFromStickyQueue());
    } else {
      assertFalse(slotInfo.isFromStickyQueue());
    }
  }

  private static final class RecordingSlotSupplier implements SlotSupplier<WorkflowSlotInfo> {
    private final SlotPermit reservedPermit = new SlotPermit();
    private SlotMarkUsedContext<WorkflowSlotInfo> markUsedContext;
    private SlotReleaseContext<WorkflowSlotInfo> releaseContext;

    @Override
    public SlotSupplierFuture reserveSlot(
        io.temporal.worker.tuning.SlotReserveContext<WorkflowSlotInfo> ctx) {
      return SlotSupplierFuture.completedFuture(reservedPermit);
    }

    @Override
    public Optional<SlotPermit> tryReserveSlot(
        io.temporal.worker.tuning.SlotReserveContext<WorkflowSlotInfo> ctx) {
      return Optional.of(reservedPermit);
    }

    @Override
    public void markSlotUsed(SlotMarkUsedContext<WorkflowSlotInfo> ctx) {
      markUsedContext = ctx;
    }

    @Override
    public void releaseSlot(SlotReleaseContext<WorkflowSlotInfo> ctx) {
      releaseContext = ctx;
    }
  }
}
