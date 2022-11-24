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
import io.temporal.workflow.Functions;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class LocalActivityExecutionContext {
  private final @Nonnull ExecuteLocalActivityParameters executionParams;
  private final @Nonnull Deadline localRetryDeadline;
  private final @Nonnull CompletableFuture<LocalActivityResult> executionResult =
      new CompletableFuture<>();

  private final @Nullable Deadline scheduleToCloseDeadline;

  private final @Nonnull AtomicInteger currentAttempt;

  private final @Nonnull AtomicReference<Failure> lastFailure = new AtomicReference<>();

  private @Nullable ScheduledFuture<?> scheduleToCloseFuture;

  public LocalActivityExecutionContext(
      @Nonnull ExecuteLocalActivityParameters executionParams,
      @Nonnull Functions.Proc1<LocalActivityResult> resultCallback,
      @Nonnull Deadline localRetryDeadline,
      @Nullable Deadline scheduleToCloseDeadline) {
    this.executionParams = Objects.requireNonNull(executionParams, "executionParams");
    this.executionResult.thenAccept(
        Objects.requireNonNull(resultCallback, "resultCallback")::apply);
    this.localRetryDeadline = localRetryDeadline;
    this.scheduleToCloseDeadline = scheduleToCloseDeadline;
    this.currentAttempt = new AtomicInteger(executionParams.getInitialAttempt());
  }

  public String getActivityId() {
    return executionParams.getActivityId();
  }

  @Nonnull
  public ExecuteLocalActivityParameters getExecutionParams() {
    return executionParams;
  }

  @Nonnull
  public Deadline getLocalRetryDeadline() {
    return localRetryDeadline;
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
  public Failure getLastFailure() {
    return lastFailure.get();
  }

  @Nonnull
  public PollActivityTaskQueueResponse getNextAttemptActivityTask(@Nullable Failure lastFailure) {
    // synchronization here is not absolutely needed as LocalActivityWorker#scheduleNextAttempt
    // shouldn't be executed concurrently. But to make sure this code is safe for future changes,
    // let's make this method atomic and protect thread-unsafe protobuf builder modification.

    // executionResult here is used just as an internal monitor object that is final and never
    // escapes the class
    synchronized (executionResult) {
      int nextAttempt = currentAttempt.incrementAndGet();
      if (lastFailure != null) {
        this.lastFailure.set(lastFailure);
      }
      return executionParams.getActivityTaskBuilder().setAttempt(nextAttempt).build();
    }
  }

  @Nullable
  public Deadline getScheduleToCloseDeadline() {
    return scheduleToCloseDeadline;
  }

  public void setScheduleToCloseFuture(@Nullable ScheduledFuture<?> scheduleToCloseFuture) {
    this.scheduleToCloseFuture = scheduleToCloseFuture;
  }

  public boolean callback(LocalActivityResult result) {
    if (scheduleToCloseFuture != null) {
      scheduleToCloseFuture.cancel(false);
    }
    return executionResult.complete(result);
  }
}
