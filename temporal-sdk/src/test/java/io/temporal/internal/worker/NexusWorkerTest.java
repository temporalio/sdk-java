package io.temporal.internal.worker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.nexus.v1.Request;
import io.temporal.api.nexus.v1.Response;
import io.temporal.api.nexus.v1.StartOperationRequest;
import io.temporal.api.nexus.v1.StartOperationResponse;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.PollNexusTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollNexusTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondNexusTaskFailedRequest;
import io.temporal.api.workflowservice.v1.ShutdownWorkerRequest;
import io.temporal.api.workflowservice.v1.ShutdownWorkerResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.payload.storage.ExternalStorageRunner;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.tuning.FixedSizeSlotSupplier;
import io.temporal.worker.tuning.PollerBehaviorSimpleMaximum;
import io.temporal.worker.tuning.SlotSupplier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import org.junit.Test;
import org.mockito.stubbing.Answer;

public class NexusWorkerTest {

  @Test
  public void interruptingShutdownCancelsInFlightStorage() throws Exception {
    NexusWorker worker = worker();

    try (ShutdownManager shutdownManager = new ShutdownManager()) {
      worker.shutdown(shutdownManager, true).get();
    }

    assertTrue(worker.storageCancellation.token().isCancellationRequested());
  }

  @Test
  public void gracefulShutdownLeavesStorageRunning() throws Exception {
    NexusWorker worker = worker();

    try (ShutdownManager shutdownManager = new ShutdownManager()) {
      worker.shutdown(shutdownManager, false).get();
    }

    assertFalse(worker.storageCancellation.token().isCancellationRequested());
  }

  /**
   * A forced shutdown cancels in-flight external storage. That cancellation means we abandoned the
   * task, not that the handler failed, so nothing may be reported to the server.
   */
  @Test
  public void storageCancelledByAForcedShutdownIsNotReportedAsATaskFailure() throws Exception {
    CountDownLatch storeEntered = new CountDownLatch(1);
    // Never completes: the store is still in flight when the shutdown cancels it.
    BlockingDriver driver = new BlockingDriver(storeEntered, new CompletableFuture<>());

    Fixture fixture = new Fixture(driver);
    assertTrue(fixture.worker.start());
    assertTrue("the store must be reached", storeEntered.await(10, TimeUnit.SECONDS));

    try (ShutdownManager shutdownManager = new ShutdownManager()) {
      fixture.worker.shutdown(shutdownManager, true).get();
    }

    verify(fixture.blockingStub, never()).respondNexusTaskFailed(any());
  }

  /**
   * Cancelling storage means we abandoned the work. Storage genuinely breaking at the same moment
   * is a different thing and must still reach the server.
   */
  @Test
  public void storageBreakingDuringAForcedShutdownIsStillReported() throws Exception {
    CountDownLatch storeEntered = new CountDownLatch(1);
    CompletableFuture<List<StorageDriverClaim>> broken = new CompletableFuture<>();
    broken.completeExceptionally(new IllegalStateException("storage unavailable"));
    BlockingDriver driver = new BlockingDriver(storeEntered, broken);

    Fixture fixture = new Fixture(driver);
    // Already shutting down when the task runs, so a real failure has to survive the cancellation.
    fixture.worker.storageCancellation.cancel();
    CountDownLatch reported = new CountDownLatch(1);
    when(fixture.blockingStub.respondNexusTaskFailed(any()))
        .thenAnswer(
            (Answer<Object>)
                invocation -> {
                  reported.countDown();
                  return null;
                });

    assertTrue(fixture.worker.start());
    assertTrue(
        "a real storage failure must still be reported", reported.await(10, TimeUnit.SECONDS));

    try (ShutdownManager shutdownManager = new ShutdownManager()) {
      fixture.worker.shutdown(shutdownManager, true).get();
    }
    verify(fixture.blockingStub).respondNexusTaskFailed(any(RespondNexusTaskFailedRequest.class));
  }

  /** A worker wired to {@code driver}, polling exactly one nexus task that returns a payload. */
  private static final class Fixture {
    final NexusWorker worker;
    final WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub;

    Fixture(StorageDriver driver) throws Exception {
      WorkflowServiceStubs service = mock(WorkflowServiceStubs.class);
      when(service.getServerCapabilities())
          .thenReturn(() -> GetSystemInfoResponse.Capabilities.getDefaultInstance());

      blockingStub = mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
      WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub =
          mock(WorkflowServiceGrpc.WorkflowServiceFutureStub.class);
      when(futureStub.shutdownWorker(any(ShutdownWorkerRequest.class)))
          .thenReturn(Futures.immediateFuture(ShutdownWorkerResponse.newBuilder().build()));
      when(service.blockingStub()).thenReturn(blockingStub);
      when(service.futureStub()).thenReturn(futureStub);
      when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);

      PollNexusTaskQueueResponse pollResponse =
          PollNexusTaskQueueResponse.newBuilder()
              .setTaskToken(ByteString.copyFrom("token", UTF_8))
              .setRequest(
                  Request.newBuilder()
                      .setStartOperation(
                          StartOperationRequest.newBuilder()
                              .setService("service")
                              .setOperation("operation")))
              .build();
      CountDownLatch blockPolls = new CountDownLatch(1);
      when(blockingStub.pollNexusTaskQueue(any(PollNexusTaskQueueRequest.class)))
          .thenReturn(pollResponse)
          .thenAnswer(
              (Answer<PollNexusTaskQueueResponse>)
                  invocation -> {
                    blockPolls.await();
                    return null;
                  });

      NexusTaskHandler handler = mock(NexusTaskHandler.class);
      when(handler.start()).thenReturn(true);
      Payload result = Payload.newBuilder().setData(ByteString.copyFrom("a result", UTF_8)).build();
      when(handler.handle(any(), any()))
          .thenReturn(
              new NexusTaskHandler.Result(
                  Response.newBuilder()
                      .setStartOperation(
                          StartOperationResponse.newBuilder()
                              .setSyncSuccess(
                                  StartOperationResponse.Sync.newBuilder().setPayload(result)))
                      .build()));

      worker =
          new NexusWorker(
              service,
              "namespace",
              "task_queue",
              SingleWorkerOptions.newBuilder()
                  .setIdentity("test_identity")
                  .setBuildId(UUID.randomUUID().toString())
                  .setWorkerInstanceKey(UUID.randomUUID().toString())
                  .setPollerOptions(
                      PollerOptions.newBuilder()
                          .setPollerBehavior(new PollerBehaviorSimpleMaximum(1))
                          .build())
                  .setMetricsScope(new NoopScope())
                  .setExternalStorageRunner(
                      ExternalStorageRunner.create(
                          ExternalStorage.newBuilder()
                              .setDriver(driver)
                              .setPayloadSizeThreshold(0)
                              .build()))
                  .build(),
              handler,
              DefaultDataConverter.newDefaultInstance(),
              new FixedSizeSlotSupplier<>(10),
              new NamespaceCapabilities());
    }
  }

  /** Signals when a store is reached and answers every store with {@code answer}. */
  private static final class BlockingDriver implements StorageDriver {
    private final CountDownLatch storeEntered;
    private final CompletableFuture<List<StorageDriverClaim>> answer;
    final AtomicInteger stores = new AtomicInteger();

    BlockingDriver(
        CountDownLatch storeEntered, CompletableFuture<List<StorageDriverClaim>> answer) {
      this.storeEntered = storeEntered;
      this.answer = answer;
    }

    @Override
    @Nonnull
    public String getName() {
      return "blocking";
    }

    @Override
    @Nonnull
    public String getType() {
      return "test.nexus.blocking";
    }

    @Override
    @Nonnull
    public CompletableFuture<List<StorageDriverClaim>> store(
        @Nonnull StorageDriverStoreContext context, @Nonnull List<Payload> payloads) {
      stores.incrementAndGet();
      storeEntered.countDown();
      return answer;
    }

    @Override
    @Nonnull
    public CompletableFuture<List<Payload>> retrieve(
        @Nonnull StorageDriverRetrieveContext context, @Nonnull List<StorageDriverClaim> claims) {
      List<Payload> payloads =
          new ArrayList<>(Collections.nCopies(claims.size(), Payload.getDefaultInstance()));
      return CompletableFuture.completedFuture(payloads);
    }
  }

  @SuppressWarnings("unchecked")
  private static NexusWorker worker() {
    WorkflowServiceStubs service = mock(WorkflowServiceStubs.class);
    when(service.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.getDefaultInstance());
    return new NexusWorker(
        service,
        "ns",
        "tq",
        SingleWorkerOptions.newBuilder().build(),
        mock(NexusTaskHandler.class),
        DefaultDataConverter.newDefaultInstance(),
        mock(SlotSupplier.class),
        mock(NamespaceCapabilities.class));
  }
}
