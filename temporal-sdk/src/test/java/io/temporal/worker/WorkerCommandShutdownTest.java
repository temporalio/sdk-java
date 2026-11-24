package io.temporal.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.TaskQueueKind;
import io.temporal.api.enums.v1.TaskQueueType;
import io.temporal.api.namespace.v1.NamespaceInfo;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.WorkflowClient;
import io.temporal.internal.testservice.InProcessGRPCServer;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class WorkerCommandShutdownTest {
  private static final String ACTIVITY_QUEUE = "activity-queue";
  private static final String ACTIVITY_TYPE = "DrainActivity";

  @ActivityInterface
  public interface DrainActivity {
    @ActivityMethod(name = ACTIVITY_TYPE)
    void run();
  }

  @Test(timeout = 20_000)
  public void shutdownDrainsActivitiesAndDoesNotSuppressReplacementCommandPoll() throws Exception {
    AtomicBoolean activityDispatched = new AtomicBoolean();
    CompletableFuture<Void> activityStarted = new CompletableFuture<>();
    CompletableFuture<Void> finishActivity = new CompletableFuture<>();
    CompletableFuture<Void> activityShutdownRequested = new CompletableFuture<>();
    CompletableFuture<PollNexusTaskQueueRequest> firstPollStarted = new CompletableFuture<>();
    CompletableFuture<PollNexusTaskQueueRequest> secondPollStarted = new CompletableFuture<>();
    CompletableFuture<PollNexusTaskQueueRequest> replacementPollStarted = new CompletableFuture<>();
    CompletableFuture<Void> replacementPollSuppressed = new CompletableFuture<>();
    CompletableFuture<Void> pollCancelled = new CompletableFuture<>();
    CompletableFuture<Void> completeFirstPoll = new CompletableFuture<>();
    CompletableFuture<Void> completeSecondPoll = new CompletableFuture<>();
    CompletableFuture<ShutdownWorkerRequest> shutdownRequested = new CompletableFuture<>();
    AtomicInteger pollCount = new AtomicInteger();
    WorkflowServiceGrpc.WorkflowServiceImplBase service =
        new WorkerCommandService() {
          @Override
          public void pollActivityTaskQueue(
              PollActivityTaskQueueRequest request,
              StreamObserver<PollActivityTaskQueueResponse> response) {
            if (activityDispatched.getAndSet(true)) {
              activityShutdownRequested.thenRun(
                  () -> {
                    response.onNext(PollActivityTaskQueueResponse.getDefaultInstance());
                    response.onCompleted();
                  });
              return;
            }
            response.onNext(
                PollActivityTaskQueueResponse.newBuilder()
                    .setTaskToken(ByteString.copyFromUtf8("activity-token"))
                    .setActivityId("activity-id")
                    .setActivityType(ActivityType.newBuilder().setName(ACTIVITY_TYPE))
                    .setWorkflowType(WorkflowType.newBuilder().setName("Workflow"))
                    .setWorkflowExecution(
                        WorkflowExecution.newBuilder()
                            .setWorkflowId("workflow-id")
                            .setRunId("run-id"))
                    .build());
            response.onCompleted();
          }

          @Override
          public void respondActivityTaskCompleted(
              RespondActivityTaskCompletedRequest request,
              StreamObserver<RespondActivityTaskCompletedResponse> response) {
            response.onNext(RespondActivityTaskCompletedResponse.getDefaultInstance());
            response.onCompleted();
          }

          @Override
          public void pollNexusTaskQueue(
              PollNexusTaskQueueRequest request,
              StreamObserver<PollNexusTaskQueueResponse> response) {
            int pollNumber = pollCount.incrementAndGet();
            if (pollNumber > 2) {
              ShutdownWorkerRequest shutdown = shutdownRequested.getNow(null);
              // Matching returns an empty response for late polls with a shut-down instance key.
              if (shutdown != null
                  && shutdown.getWorkerInstanceKey().equals(request.getWorkerInstanceKey())) {
                replacementPollSuppressed.complete(null);
                response.onNext(PollNexusTaskQueueResponse.getDefaultInstance());
                response.onCompleted();
              } else {
                replacementPollStarted.complete(request);
              }
              return;
            }
            boolean first = pollNumber == 1;
            ((ServerCallStreamObserver<?>) response)
                .setOnCancelHandler(() -> pollCancelled.complete(null));
            (first ? completeFirstPoll : completeSecondPoll)
                .thenRun(
                    () -> {
                      response.onNext(PollNexusTaskQueueResponse.getDefaultInstance());
                      response.onCompleted();
                    });
            (first ? firstPollStarted : secondPollStarted).complete(request);
          }

          @Override
          public void shutdownWorker(
              ShutdownWorkerRequest request, StreamObserver<ShutdownWorkerResponse> response) {
            if (ACTIVITY_QUEUE.equals(request.getTaskQueue())) {
              activityShutdownRequested.complete(null);
            } else {
              shutdownRequested.complete(request);
            }
            response.onNext(ShutdownWorkerResponse.getDefaultInstance());
            response.onCompleted();
          }
        };
    InProcessGRPCServer server = new InProcessGRPCServer(Collections.singletonList(service));
    WorkflowServiceStubs stubs =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder().setChannel(server.getChannel()).build());
    WorkflowClient client = WorkflowClient.newInstance(stubs);
    WorkerFactory factory = WorkerFactory.newInstance(client);
    WorkerFactory replacementFactory = WorkerFactory.newInstance(client);
    try {
      Worker worker =
          factory.newWorker(
              ACTIVITY_QUEUE,
              WorkerOptions.newBuilder().setMaxConcurrentActivityTaskPollers(1).build());
      worker.registerActivitiesImplementations(
          new DrainActivity() {
            @Override
            public void run() {
              activityStarted.complete(null);
              finishActivity.join();
            }
          });
      factory.start();
      firstPollStarted.get(5, TimeUnit.SECONDS);
      activityStarted.get(5, TimeUnit.SECONDS);

      factory.shutdown();
      activityShutdownRequested.get(5, TimeUnit.SECONDS);
      completeFirstPoll.complete(null);
      CompletableFuture.anyOf(secondPollStarted, pollCancelled, shutdownRequested)
          .get(5, TimeUnit.SECONDS);
      assertFalse("Factory shutdown interrupted the command poll", pollCancelled.isDone());
      assertFalse(
          "Factory stopped command polling before the activity drained",
          shutdownRequested.isDone());
      PollNexusTaskQueueRequest poll = secondPollStarted.get(5, TimeUnit.SECONDS);
      assertEquals(TaskQueueKind.TASK_QUEUE_KIND_WORKER_COMMANDS, poll.getTaskQueue().getKind());
      assertFalse(poll.getWorkerInstanceKey().isEmpty());

      finishActivity.complete(null);
      CompletableFuture.anyOf(shutdownRequested, pollCancelled).get(5, TimeUnit.SECONDS);
      assertFalse("Factory shutdown interrupted the command poll", pollCancelled.isDone());
      ShutdownWorkerRequest shutdown = shutdownRequested.get(5, TimeUnit.SECONDS);
      assertEquals(poll.getNamespace(), shutdown.getNamespace());
      assertEquals(poll.getIdentity(), shutdown.getIdentity());
      assertEquals(poll.getTaskQueue().getName(), shutdown.getTaskQueue());
      assertEquals(poll.getWorkerInstanceKey(), shutdown.getWorkerInstanceKey());
      assertEquals(
          Collections.singletonList(TaskQueueType.TASK_QUEUE_TYPE_NEXUS),
          shutdown.getTaskQueueTypesList());

      completeSecondPoll.complete(null);
      factory.awaitTermination(5, TimeUnit.SECONDS);
      assertTrue("Factory did not terminate", factory.isTerminated());
      assertFalse("Factory shutdown interrupted the command poll", pollCancelled.isDone());
      assertEquals(2, pollCount.get());

      replacementFactory.start();
      CompletableFuture.anyOf(replacementPollStarted, replacementPollSuppressed)
          .get(5, TimeUnit.SECONDS);
      assertFalse(
          "Factory shutdown suppressed the replacement poll", replacementPollSuppressed.isDone());
      assertEquals(
          poll.getTaskQueue(), replacementPollStarted.get(5, TimeUnit.SECONDS).getTaskQueue());
    } finally {
      finishActivity.complete(null);
      if (!factory.isShutdown()) {
        factory.shutdownNow();
      }
      if (replacementFactory.isStarted()) {
        replacementFactory.shutdownNow();
        replacementFactory.awaitTermination(5, TimeUnit.SECONDS);
      }
      stubs.shutdownNow();
      server.shutdownNow();
    }
  }

  @Test(timeout = 15_000)
  public void shutdownInterruptsCommandPollWhenShutdownRpcFails() throws Exception {
    CountDownLatch pollStarted = new CountDownLatch(1);
    CountDownLatch pollCancelled = new CountDownLatch(1);
    WorkerCommandService service =
        new WorkerCommandService() {
          @Override
          public void pollNexusTaskQueue(
              PollNexusTaskQueueRequest request,
              StreamObserver<PollNexusTaskQueueResponse> response) {
            ((ServerCallStreamObserver<?>) response).setOnCancelHandler(pollCancelled::countDown);
            pollStarted.countDown();
          }

          @Override
          public void shutdownWorker(
              ShutdownWorkerRequest request, StreamObserver<ShutdownWorkerResponse> response) {
            response.onError(Status.INTERNAL.asRuntimeException());
          }
        };
    InProcessGRPCServer server = new InProcessGRPCServer(Collections.singletonList(service));
    WorkflowServiceStubs stubs =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder().setChannel(server.getChannel()).build());
    WorkerFactory factory = WorkerFactory.newInstance(WorkflowClient.newInstance(stubs));
    try {
      factory.start();
      assertTrue("Command poll did not start", pollStarted.await(5, TimeUnit.SECONDS));

      factory.shutdown();
      assertTrue(
          "Failed shutdown RPC did not cancel the poll", pollCancelled.await(5, TimeUnit.SECONDS));
      factory.awaitTermination(5, TimeUnit.SECONDS);
      assertTrue("Factory did not terminate", factory.isTerminated());
    } finally {
      if (!factory.isShutdown()) {
        factory.shutdownNow();
      }
      stubs.shutdownNow();
      server.shutdownNow();
    }
  }

  private abstract static class WorkerCommandService
      extends WorkflowServiceGrpc.WorkflowServiceImplBase {
    @Override
    public void getSystemInfo(
        GetSystemInfoRequest request, StreamObserver<GetSystemInfoResponse> response) {
      response.onNext(GetSystemInfoResponse.getDefaultInstance());
      response.onCompleted();
    }

    @Override
    public void describeNamespace(
        DescribeNamespaceRequest request, StreamObserver<DescribeNamespaceResponse> response) {
      response.onNext(
          DescribeNamespaceResponse.newBuilder()
              .setNamespaceInfo(
                  NamespaceInfo.newBuilder()
                      .setCapabilities(
                          NamespaceInfo.Capabilities.newBuilder()
                              .setWorkerCommands(true)
                              .setWorkerPollCompleteOnShutdown(true)))
              .build());
      response.onCompleted();
    }
  }
}
