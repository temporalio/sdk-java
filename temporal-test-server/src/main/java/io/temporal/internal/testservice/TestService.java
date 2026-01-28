package io.temporal.internal.testservice;

import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import io.temporal.api.testservice.v1.*;
import io.temporal.internal.common.ProtobufTimeUtils;
import java.io.Closeable;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * In memory implementation of the Operator Service. To be used for testing purposes only.
 *
 * <p>Do not use directly, instead use {@link io.temporal.testing.TestWorkflowEnvironment}.
 */
final class TestService extends TestServiceGrpc.TestServiceImplBase implements Closeable {

  private final SelfAdvancingTimer selfAdvancingTimer;
  private final TestWorkflowStore workflowStore;

  public TestService(
      TestWorkflowStore workflowStore,
      SelfAdvancingTimer selfAdvancingTimer,
      boolean lockTimeSkipping) {
    this.workflowStore = workflowStore;
    this.selfAdvancingTimer = selfAdvancingTimer;
    if (lockTimeSkipping) {
      selfAdvancingTimer.lockTimeSkipping("TestService constructor");
    }
  }

  @Override
  public void lockTimeSkipping(
      LockTimeSkippingRequest request, StreamObserver<LockTimeSkippingResponse> responseObserver) {
    selfAdvancingTimer.lockTimeSkipping("External Caller");
    responseObserver.onNext(LockTimeSkippingResponse.newBuilder().build());
    responseObserver.onCompleted();
  }

  @Override
  public void unlockTimeSkipping(
      UnlockTimeSkippingRequest request,
      StreamObserver<UnlockTimeSkippingResponse> responseObserver) {
    selfAdvancingTimer.unlockTimeSkipping("External Caller");
    responseObserver.onNext(UnlockTimeSkippingResponse.newBuilder().build());
    responseObserver.onCompleted();
  }

  @Override
  public void sleep(SleepRequest request, StreamObserver<SleepResponse> responseObserver) {
    CompletableFuture<Void> result = new CompletableFuture<>();
    selfAdvancingTimer.schedule(
        ProtobufTimeUtils.toJavaDuration(request.getDuration()),
        () -> result.complete(null),
        "TestService sleep");
    try {
      result.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }

    responseObserver.onNext(SleepResponse.newBuilder().build());
    responseObserver.onCompleted();
  }

  @Override
  public void sleepUntil(
      SleepUntilRequest request, StreamObserver<SleepResponse> responseObserver) {
    CompletableFuture<Void> result = new CompletableFuture<>();
    selfAdvancingTimer.scheduleAt(
        ProtobufTimeUtils.toJavaInstant(request.getTimestamp()),
        () -> result.complete(null),
        "TestService sleepUntil");
    try {
      result.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }

    responseObserver.onNext(SleepResponse.newBuilder().build());
    responseObserver.onCompleted();
  }

  @Override
  public void unlockTimeSkippingWithSleep(
      SleepRequest request, StreamObserver<SleepResponse> responseObserver) {
    unlockTimeSkippingWhileSleep(ProtobufTimeUtils.toJavaDuration(request.getDuration()));
    responseObserver.onNext(SleepResponse.newBuilder().build());
    responseObserver.onCompleted();
  }

  @Override
  public void getCurrentTime(
      Empty request, StreamObserver<GetCurrentTimeResponse> responseObserver) {
    Timestamp timestamp = workflowStore.currentTime();
    responseObserver.onNext(GetCurrentTimeResponse.newBuilder().setTime(timestamp).build());
    responseObserver.onCompleted();
  }

  /**
   * Unlocks time skipping and blocks the calling thread until internal clock passes the current +
   * duration time.<br>
   * When the time is reached, locks time skipping and returns.<br>
   * Might not block at all due to time skipping. Or might block if the time skipping lock counter
   * was more than 1.
   */
  private void unlockTimeSkippingWhileSleep(Duration duration) {
    CompletableFuture<Void> result = new CompletableFuture<>();
    selfAdvancingTimer.schedule(
        duration,
        () -> {
          selfAdvancingTimer.lockTimeSkipping("TestService unlockTimeSkippingWhileSleep");
          result.complete(null);
        },
        "TestService unlockTimeSkippingWhileSleep");
    selfAdvancingTimer.unlockTimeSkipping("TestService unlockTimeSkippingWhileSleep");
    try {
      result.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void close() {}
}
