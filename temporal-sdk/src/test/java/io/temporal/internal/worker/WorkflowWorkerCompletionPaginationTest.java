package io.temporal.internal.worker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.RecordMarkerCommandAttributes;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.errordetails.v1.WorkflowTaskCompletionBufferLostFailure;
import io.temporal.api.namespace.v1.NamespaceInfo;
import io.temporal.api.protocol.v1.Message;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse;
import io.temporal.api.workflowservice.v1.ShutdownWorkerRequest;
import io.temporal.api.workflowservice.v1.ShutdownWorkerResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.serviceclient.StatusUtils;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.tuning.FixedSizeSlotSupplier;
import io.temporal.worker.tuning.PollerBehaviorSimpleMaximum;
import io.temporal.worker.tuning.SlotMarkUsedContext;
import io.temporal.worker.tuning.SlotPermit;
import io.temporal.worker.tuning.SlotReleaseContext;
import io.temporal.worker.tuning.SlotReserveContext;
import io.temporal.worker.tuning.SlotSupplier;
import io.temporal.worker.tuning.SlotSupplierFuture;
import io.temporal.worker.tuning.WorkflowSlotInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class WorkflowWorkerCompletionPaginationTest {

  private static final ByteString TASK_TOKEN = ByteString.copyFrom("token", UTF_8);
  private static final String FINAL_PAGE = "final";

  // Four 1 MiB commands exceed MAX_PAGE_BYTES, so the completion is split into several pages.
  private static final int COMMAND_COUNT = 4;

  @Test
  public void aPaginatedCompletionSendsEveryIntermediatePageBeforeTheFinalPage() throws Exception {
    RespondWorkflowTaskCompletedRequest completion =
        oversizedCompletion().toBuilder()
            .addMessages(Message.newBuilder().setId("message").build())
            .build();

    List<RespondWorkflowTaskCompletedRequest> sent =
        completeTask(true, completion, (page, worker) -> success());

    assertEquals(attempts(1), labels(sent));
    int commandCount = 0;
    for (RespondWorkflowTaskCompletedRequest page : sent) {
      assertEquals(TASK_TOKEN, page.getTaskToken());
      if (page.getIntermediatePage()) {
        assertEquals(0, page.getMessagesCount());
        commandCount += page.getCommandsCount();
      } else {
        assertEquals(0, page.getCommandsCount());
        assertEquals(1, page.getMessagesCount());
      }
    }
    assertEquals(COMMAND_COUNT, commandCount);
  }

  @Test
  public void aCompletionIsSentAsOneRequestWhenTheNamespaceDoesNotSupportPagination()
      throws Exception {
    List<RespondWorkflowTaskCompletedRequest> sent =
        completeTask(false, oversizedCompletion(), (page, worker) -> success());

    assertEquals(1, sent.size());
    assertFalse(sent.get(0).getIntermediatePage());
    assertEquals(0, sent.get(0).getPageNumber());
    assertEquals(COMMAND_COUNT, sent.get(0).getCommandsCount());
  }

  @Test
  public void bufferLossOnTheFinalPageResendsEveryPageFromTheFirst() throws Exception {
    AtomicInteger finalAttempts = new AtomicInteger();
    List<RespondWorkflowTaskCompletedRequest> sent =
        completeTask(
            true,
            oversizedCompletion(),
            (page, worker) -> {
              if (!page.getIntermediatePage() && finalAttempts.incrementAndGet() <= 2) {
                throw bufferLost();
              }
              return success();
            });

    // Each attempt sends the final page exactly once, so the gRPC retryer must not have retried it.
    assertEquals(attempts(3), labels(sent));
  }

  @Test
  public void bufferLossOnAnIntermediatePageResendsEveryPageFromTheFirst() throws Exception {
    AtomicInteger lastPageAttempts = new AtomicInteger();
    int lastIntermediatePage = intermediatePageCount() - 1;
    List<RespondWorkflowTaskCompletedRequest> sent =
        completeTask(
            true,
            oversizedCompletion(),
            (page, worker) -> {
              if (page.getIntermediatePage()
                  && page.getPageNumber() == lastIntermediatePage
                  && lastPageAttempts.incrementAndGet() == 1) {
                throw bufferLost();
              }
              return success();
            });

    List<String> expected = new ArrayList<>(attempts(1));
    expected.remove(FINAL_PAGE);
    expected.addAll(attempts(1));
    assertEquals(expected, labels(sent));
  }

  @Test
  public void otherErrorsAreNotResent() throws Exception {
    AtomicInteger finalAttempts = new AtomicInteger();
    List<RespondWorkflowTaskCompletedRequest> sent =
        completeTask(
            true,
            oversizedCompletion(),
            (page, worker) -> {
              // Accepting a resend lets a regression fail the assertion instead of looping.
              if (!page.getIntermediatePage() && finalAttempts.incrementAndGet() == 1) {
                // What the server returns once the task has timed out and the token is stale.
                throw Status.NOT_FOUND
                    .withDescription("workflow task not found")
                    .asRuntimeException();
              }
              return success();
            });

    assertEquals(attempts(1), labels(sent));
  }

  @Test
  public void shutdownStopsBufferLossResends() throws Exception {
    AtomicInteger finalAttempts = new AtomicInteger();
    List<RespondWorkflowTaskCompletedRequest> sent =
        completeTask(
            true,
            oversizedCompletion(),
            (page, worker) -> {
              // Accepting a resend lets a regression fail the assertion instead of looping.
              if (!page.getIntermediatePage() && finalAttempts.incrementAndGet() == 1) {
                // Shut down while the first attempt is in flight, so the resend loop observes it.
                worker.shutdown(new ShutdownManager(), false);
                throw bufferLost();
              }
              return success();
            });

    assertEquals(attempts(1), labels(sent));
  }

  @FunctionalInterface
  private interface CompletionAnswer {
    RespondWorkflowTaskCompletedResponse answer(
        RespondWorkflowTaskCompletedRequest page, WorkflowWorker worker);
  }

  /**
   * Runs one workflow task that produces {@code completion} and returns every completion request
   * the worker sent. The worker is shut down only after the task finishes, because the resend loop
   * stops early on shutdown.
   */
  private static List<RespondWorkflowTaskCompletedRequest> completeTask(
      boolean paginationEnabled,
      RespondWorkflowTaskCompletedRequest completion,
      CompletionAnswer answer)
      throws Exception {
    NamespaceCapabilities namespaceCapabilities = new NamespaceCapabilities();
    namespaceCapabilities.setFromCapabilities(
        NamespaceInfo.Capabilities.newBuilder()
            .setWorkflowTaskCompletionPagination(paginationEnabled)
            .build());

    WorkflowServiceStubs client = mock(WorkflowServiceStubs.class);
    when(client.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.newBuilder().build());
    WorkflowRunLockManager runLockManager = new WorkflowRunLockManager();
    WorkflowExecutorCache cache = new WorkflowExecutorCache(10, runLockManager, new NoopScope());
    WorkflowTaskHandler taskHandler = mock(WorkflowTaskHandler.class);
    when(taskHandler.isAnyTypeSupported()).thenReturn(true);
    when(taskHandler.handleWorkflowTask(any(PollWorkflowTaskQueueResponse.class), any()))
        .thenReturn(
            new WorkflowTaskHandler.Result(
                "workflow-type", completion, null, null, null, false, null, null));
    TaskFinishedSlotSupplier slotSupplier = new TaskFinishedSlotSupplier();

    WorkflowWorker worker =
        new WorkflowWorker(
            client,
            "default",
            "task_queue",
            "sticky_task_queue",
            SingleWorkerOptions.newBuilder()
                .setIdentity("test_identity")
                .setBuildId(UUID.randomUUID().toString())
                .setWorkerInstanceKey(UUID.randomUUID().toString())
                .setPollerOptions(
                    PollerOptions.newBuilder()
                        .setPollerBehavior(new PollerBehaviorSimpleMaximum(1))
                        .build())
                .build(),
            runLockManager,
            cache,
            taskHandler,
            mock(EagerActivityDispatcher.class),
            3,
            slotSupplier,
            namespaceCapabilities);

    WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub =
        mock(WorkflowServiceGrpc.WorkflowServiceFutureStub.class);
    when(futureStub.shutdownWorker(any(ShutdownWorkerRequest.class)))
        .thenReturn(Futures.immediateFuture(ShutdownWorkerResponse.newBuilder().build()));
    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(client.blockingStub()).thenReturn(blockingStub);
    when(client.futureStub()).thenReturn(futureStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);

    List<RespondWorkflowTaskCompletedRequest> sent = new CopyOnWriteArrayList<>();
    when(blockingStub.respondWorkflowTaskCompleted(any(RespondWorkflowTaskCompletedRequest.class)))
        .thenAnswer(
            invocation -> {
              RespondWorkflowTaskCompletedRequest page = invocation.getArgument(0);
              sent.add(page);
              return answer.answer(page, worker);
            });

    PollWorkflowTaskQueueResponse pollResponse =
        PollWorkflowTaskQueueResponse.newBuilder()
            .setTaskToken(TASK_TOKEN)
            .setWorkflowExecution(
                WorkflowExecution.newBuilder().setWorkflowId("workflow-id").setRunId("run-id"))
            .setWorkflowType(WorkflowType.newBuilder().setName("workflow-type"))
            .build();
    CountDownLatch blockPolls = new CountDownLatch(1);
    when(blockingStub.pollWorkflowTaskQueue(any(PollWorkflowTaskQueueRequest.class)))
        .thenReturn(pollResponse)
        .thenAnswer(
            invocation -> {
              blockPolls.await();
              return null;
            });

    assertTrue(worker.start());
    assertTrue(
        "workflow task did not finish", slotSupplier.taskFinished.await(30, TimeUnit.SECONDS));
    worker.shutdown(new ShutdownManager(), false).get();
    return sent;
  }

  private static RespondWorkflowTaskCompletedRequest oversizedCompletion() {
    RespondWorkflowTaskCompletedRequest.Builder completion =
        RespondWorkflowTaskCompletedRequest.newBuilder();
    for (int i = 0; i < COMMAND_COUNT; i++) {
      completion.addCommands(
          Command.newBuilder()
              .setCommandType(CommandType.COMMAND_TYPE_RECORD_MARKER)
              .setRecordMarkerCommandAttributes(
                  RecordMarkerCommandAttributes.newBuilder()
                      .setMarkerName("marker")
                      .putDetails(
                          "data",
                          Payloads.newBuilder()
                              .addPayloads(
                                  Payload.newBuilder()
                                      .setData(ByteString.copyFrom(new byte[1024 * 1024])))
                              .build())));
    }
    return completion.build();
  }

  private static int intermediatePageCount() {
    int count =
        WorkflowTaskCompletionPaginator.paginate(
                oversizedCompletion(), WorkflowTaskCompletionPaginator.MAX_PAGE_BYTES)
            .intermediatePages
            .size();
    assertTrue("expected at least two intermediate pages, got " + count, count >= 2);
    return count;
  }

  /** The page labels of {@code count} complete send attempts, each ending with the final page. */
  private static List<String> attempts(int count) {
    List<String> labels = new ArrayList<>();
    int pages = intermediatePageCount();
    for (int attempt = 0; attempt < count; attempt++) {
      for (int page = 0; page < pages; page++) {
        labels.add(Integer.toString(page));
      }
      labels.add(FINAL_PAGE);
    }
    return labels;
  }

  private static List<String> labels(List<RespondWorkflowTaskCompletedRequest> sent) {
    List<String> labels = new ArrayList<>();
    for (RespondWorkflowTaskCompletedRequest page : sent) {
      if (page.getIntermediatePage()) {
        labels.add(Integer.toString(page.getPageNumber()));
      } else {
        assertEquals(intermediatePageCount(), page.getPageNumber());
        labels.add(FINAL_PAGE);
      }
    }
    return Collections.unmodifiableList(labels);
  }

  private static RespondWorkflowTaskCompletedResponse success() {
    return RespondWorkflowTaskCompletedResponse.getDefaultInstance();
  }

  private static StatusRuntimeException bufferLost() {
    return StatusUtils.newException(
        Status.ABORTED.withDescription("buffer lost"),
        WorkflowTaskCompletionBufferLostFailure.getDefaultInstance(),
        WorkflowTaskCompletionBufferLostFailure.getDescriptor());
  }

  /** Signals once a slot that ran a task is released, after the task's completion is reported. */
  private static final class TaskFinishedSlotSupplier implements SlotSupplier<WorkflowSlotInfo> {
    private final SlotSupplier<WorkflowSlotInfo> inner = new FixedSizeSlotSupplier<>(10);
    private final Set<SlotPermit> usedPermits = ConcurrentHashMap.newKeySet();
    final CountDownLatch taskFinished = new CountDownLatch(1);

    @Override
    public SlotSupplierFuture reserveSlot(SlotReserveContext<WorkflowSlotInfo> ctx)
        throws Exception {
      return inner.reserveSlot(ctx);
    }

    @Override
    public Optional<SlotPermit> tryReserveSlot(SlotReserveContext<WorkflowSlotInfo> ctx) {
      return inner.tryReserveSlot(ctx);
    }

    @Override
    public void markSlotUsed(SlotMarkUsedContext<WorkflowSlotInfo> ctx) {
      usedPermits.add(ctx.getSlotPermit());
      inner.markSlotUsed(ctx);
    }

    @Override
    public void releaseSlot(SlotReleaseContext<WorkflowSlotInfo> ctx) {
      inner.releaseSlot(ctx);
      if (usedPermits.remove(ctx.getSlotPermit())) {
        taskFinished.countDown();
      }
    }

    @Override
    public Optional<Integer> getMaximumSlots() {
      return inner.getMaximumSlots();
    }
  }
}
