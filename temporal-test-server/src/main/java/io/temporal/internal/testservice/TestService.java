/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.testservice;

import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import io.temporal.api.testservice.v1.*;
import io.temporal.internal.common.ProtobufTimeUtils;
import java.io.Closeable;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * In memory implementation of the Operator Service. To be used for testing purposes only.
 *
 * <p>Do not use directly, instead use {@link io.temporal.testing.TestWorkflowEnvironment}.
 */
final class TestService extends TestServiceGrpc.TestServiceImplBase implements Closeable {

  private final TestWorkflowStore workflowStore;

  public TestService(TestWorkflowStore workflowStore, boolean lockTimeSkipping) {
    this.workflowStore = workflowStore;
    if (lockTimeSkipping) {
      workflowStore.getTimer().lockTimeSkipping("TestService constructor");
    }
  }

  @Override
  public void lockTimeSkipping(
      LockTimeSkippingRequest request, StreamObserver<LockTimeSkippingResponse> responseObserver) {
    workflowStore.getTimer().lockTimeSkipping("External Caller");
    responseObserver.onNext(LockTimeSkippingResponse.newBuilder().build());
    responseObserver.onCompleted();
  }

  @Override
  public void unlockTimeSkipping(
      UnlockTimeSkippingRequest request,
      StreamObserver<UnlockTimeSkippingResponse> responseObserver) {
    workflowStore.getTimer().unlockTimeSkipping("External Caller");
    responseObserver.onNext(UnlockTimeSkippingResponse.newBuilder().build());
    responseObserver.onCompleted();
  }

  @Override
  public void sleep(SleepRequest request, StreamObserver<SleepResponse> responseObserver) {
    CompletableFuture<Void> result = new CompletableFuture<>();
    workflowStore
        .getTimer()
        .schedule(
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
    workflowStore
        .getTimer()
        .scheduleAt(
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
  public void skip(SkipRequest request, StreamObserver<SkipResponse> responseObserver) {
    Instant newTimestamp =
        workflowStore.getTimer().skip(ProtobufTimeUtils.toJavaDuration(request.getDuration()));
    responseObserver.onNext(
        SkipResponse.newBuilder()
            .setNewTimestamp(ProtobufTimeUtils.toProtoTimestamp(newTimestamp))
            .build());
    responseObserver.onCompleted();
  }

  @Override
  public void skipTo(SkipToRequest request, StreamObserver<SkipToResponse> responseObserver) {
    workflowStore.getTimer().skipTo(ProtobufTimeUtils.toJavaInstant(request.getTimestamp()));
    responseObserver.onNext(SkipToResponse.newBuilder().build());
    responseObserver.onCompleted();
  }

  @Override
  public void unlockTimeSkippingWhileSleep(
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
    workflowStore
        .getTimer()
        .schedule(
            duration,
            () -> {
              workflowStore.getTimer().lockTimeSkipping("TestService unlockTimeSkippingWhileSleep");
              result.complete(null);
            },
            "TestService unlockTimeSkippingWhileSleep");
    workflowStore.getTimer().unlockTimeSkipping("TestService unlockTimeSkippingWhileSleep");
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
