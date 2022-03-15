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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * In memory implementation of the Operator Service. To be used for testing purposes only.
 *
 * <p>Do not use directly, instead use {@link io.temporal.testing.TestWorkflowEnvironment}.
 */
public final class TestService extends TestServiceGrpc.TestServiceImplBase implements Closeable {

  private final TestWorkflowStore workflowStore;

  public TestService(TestWorkflowStore workflowStore) {
    this.workflowStore = workflowStore;
  }

  @Override
  public void lockTimeSkipping(
      LockTimeSkippingRequest request, StreamObserver<LockTimeSkippingResponse> responseObserver) {
    lockTimeSkipping(request.getCallerId());
    responseObserver.onNext(LockTimeSkippingResponse.newBuilder().build());
    responseObserver.onCompleted();
  }

  @Override
  public void unlockTimeSkipping(
      UnlockTimeSkippingRequest request,
      StreamObserver<UnlockTimeSkippingResponse> responseObserver) {
    unlockTimeSkipping(request.getCallerId());
    responseObserver.onNext(UnlockTimeSkippingResponse.newBuilder().build());
    responseObserver.onCompleted();
  }

  @Override
  public void sleep(SleepRequest request, StreamObserver<SleepResponse> responseObserver) {
    sleep(ProtobufTimeUtils.toJavaDuration(request.getDuration()));
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
   * Disables time skipping. To re-enable call {@link #unlockTimeSkipping(String)}. These calls are
   * counted, so calling unlock does not guarantee that time is going to be skipped immediately as
   * another lock can be holding it.
   */
  public void lockTimeSkipping(String caller) {
    workflowStore.getTimer().lockTimeSkipping(caller);
  }

  public void unlockTimeSkipping(String caller) {
    workflowStore.getTimer().unlockTimeSkipping(caller);
  }

  /**
   * Unlocks time skipping and blocks the calling thread until internal clock passes the current +
   * duration time.<br>
   * When the time is reached, locks time skipping and returns.<br>
   * Might not block at all due to time skipping. Or might block if the time skipping lock counter
   * was more than 1.
   */
  public void sleep(Duration duration) {
    CompletableFuture<Void> result = new CompletableFuture<>();
    workflowStore
        .getTimer()
        .schedule(
            duration,
            () -> {
              workflowStore.getTimer().lockTimeSkipping("TestWorkflowService sleep");
              result.complete(null);
            },
            "workflow sleep");
    workflowStore.getTimer().unlockTimeSkipping("TestWorkflowService sleep");
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
