/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.worker;

import io.grpc.Deadline;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.workflow.v1.PendingActivityInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.internal.statemachines.ExecuteLocalActivityParameters;
import io.temporal.worker.tuning.LocalActivitySlotInfo;
import io.temporal.worker.tuning.SlotPermit;
import io.temporal.worker.tuning.SlotReleaseReason;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class LocalActivityExecutionContext {
  private final @Nonnull ExecuteLocalActivityParameters executionParams;
  private final @Nonnull AtomicInteger currentAttempt;
  private final @Nonnull AtomicReference<Failure> lastAttemptFailure = new AtomicReference<>();
  private final @Nullable Deadline scheduleToCloseDeadline;
  private @Nullable ScheduledFuture<?> scheduleToCloseFuture;
  private final @Nonnull CompletableFuture<LocalActivityResult> executionResult =
      new CompletableFuture<>();
  private @Nullable SlotPermit permit;
  private final TrackingSlotSupplier<LocalActivitySlotInfo> slotSupplier;

  public LocalActivityExecutionContext(
      @Nonnull ExecuteLocalActivityParameters executionParams,
      @Nonnull Functions.Proc1<LocalActivityResult> resultCallback,
      @Nullable Deadline scheduleToCloseDeadline,
      TrackingSlotSupplier<LocalActivitySlotInfo> slotSupplier) {
    this.executionParams = Objects.requireNonNull(executionParams, "executionParams");
    this.executionResult.thenAccept(
        Objects.requireNonNull(resultCallback, "resultCallback")::apply);
    this.scheduleToCloseDeadline = scheduleToCloseDeadline;
    this.currentAttempt = new AtomicInteger(executionParams.getInitialAttempt());
    this.slotSupplier = slotSupplier;
    Failure previousExecutionFailure = executionParams.getPreviousLocalExecutionFailure();
    if (previousExecutionFailure != null) {
      if (previousExecutionFailure.hasTimeoutFailureInfo() && previousExecutionFailure.hasCause()) {
        // It can be only startToClose timeout (others would be fatal).
        // Structure TIMEOUT_TYPE_START_TO_CLOSE -> TIMEOUT_TYPE_START_TO_CLOSE or
        // TIMEOUT_TYPE_START_TO_CLOSE -> ApplicationFailure is possible here - chaining of
        // startToClose timeout with a previous failure.
        // We reconstruct the last attempt failure (that would typically be preserved in a mutable
        // state) from local activity execution failure.
        // See a similar logic in
        // io.temporal.internal.testservice.StateMachines#timeoutActivityTask.
        lastAttemptFailure.set(Failure.newBuilder(previousExecutionFailure).clearCause().build());
      } else {
        lastAttemptFailure.set(previousExecutionFailure);
      }
    }
  }

  public String getActivityId() {
    return executionParams.getActivityId();
  }

  public int getCurrentAttempt() {
    return currentAttempt.get();
  }

  /**
   * The last failure preserved for this activity execution. This field is mimicking the behavior of
   * {@link PendingActivityInfo#getLastFailure()} that is maintained by the server in the mutable
   * state and is used to create ActivityFailures with meaningful causes and returned by {@link
   * io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceBlockingStub#describeWorkflowExecution(DescribeWorkflowExecutionRequest)}
   */
  @Nullable
  public Failure getLastAttemptFailure() {
    return lastAttemptFailure.get();
  }

  @Nullable
  public Failure getPreviousExecutionFailure() {
    return executionParams.getPreviousLocalExecutionFailure();
  }

  @Nonnull
  public PollActivityTaskQueueResponse.Builder getInitialTask() {
    return executionParams.cloneActivityTaskBuilder();
  }

  @Nonnull
  public PollActivityTaskQueueResponse.Builder getNextAttemptActivityTask(
      @Nullable Failure lastFailure) {
    // synchronization here is not absolutely needed as LocalActivityWorker#scheduleNextAttempt
    // shouldn't be executed concurrently. But to make sure this code is safe for future changes,
    // let's make this method atomic and protect thread-unsafe protobuf builder modification.

    // executionResult here is used just as an internal monitor object that is final and never
    // escapes the class
    int nextAttempt;
    synchronized (executionResult) {
      nextAttempt = currentAttempt.incrementAndGet();
      if (lastFailure != null) {
        this.lastAttemptFailure.set(lastFailure);
      }
    }
    // doesn't need to be synchronized as we clone instead of modifying the original task builder
    return executionParams
        .cloneActivityTaskBuilder()
        .setAttempt(nextAttempt)
        .clearCurrentAttemptScheduledTime();
  }

  @Nullable
  public Deadline getScheduleToCloseDeadline() {
    return scheduleToCloseDeadline;
  }

  public void setScheduleToCloseFuture(@Nullable ScheduledFuture<?> scheduleToCloseFuture) {
    this.scheduleToCloseFuture = scheduleToCloseFuture;
  }

  @Nullable
  public Duration getScheduleToStartTimeout() {
    return executionParams.getScheduleToStartTimeout();
  }

  public long getOriginalScheduledTimestamp() {
    return executionParams.getOriginalScheduledTimestamp();
  }

  @Nonnull
  public Duration getLocalRetryThreshold() {
    return executionParams.getLocalRetryThreshold();
  }

  /**
   * @return true if the execution was completed by this invocation
   */
  public boolean callback(LocalActivityResult result) {
    if (scheduleToCloseFuture != null) {
      scheduleToCloseFuture.cancel(false);
    }
    SlotReleaseReason reason = SlotReleaseReason.taskComplete();
    if (result.getProcessingError() != null) {
      reason = SlotReleaseReason.error(new Exception(result.getProcessingError().getThrowable()));
    }
    // Permit can be null in the event of a timeout while waiting on a permit
    if (permit != null) {
      slotSupplier.releaseSlot(reason, permit);
    }
    return executionResult.complete(result);
  }

  public boolean isCompleted() {
    return executionResult.isDone();
  }

  public void newAttempt() {
    executionParams.getOnNewAttemptCallback().apply();
  }

  public void setPermit(SlotPermit permit) {
    this.permit = permit;
  }

  @Nullable
  public SlotPermit getPermit() {
    return permit;
  }
}
