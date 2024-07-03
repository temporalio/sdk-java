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

package io.temporal.internal.sync;

import static io.temporal.internal.sync.WorkflowInternal.assertNotReadOnly;

import com.google.common.base.Preconditions;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.WorkflowSemaphore;
import java.time.Duration;

class WorkflowSemaphoreImpl implements WorkflowSemaphore {
  private int currentPermits;

  public WorkflowSemaphoreImpl(int permits) {
    this.currentPermits = permits;
  }

  @Override
  public void acquire() {
    acquire(1);
  }

  @Override
  public void acquire(int permits) {
    Preconditions.checkArgument(
        permits > 0, "WorkflowSemaphore.acquire called with negative permits");
    WorkflowInternal.await(
        "WorkflowSemaphore.acquire",
        () -> {
          CancellationScope.throwCanceled();
          return currentPermits >= permits;
        });
    currentPermits -= permits;
  }

  @Override
  public boolean tryAcquire() {
    return tryAcquire(1);
  }

  @Override
  public boolean tryAcquire(Duration timeout) {
    return tryAcquire(1, timeout);
  }

  @Override
  public boolean tryAcquire(int permits) {
    assertNotReadOnly("WorkflowSemaphore.tryAcquire");
    Preconditions.checkArgument(
        permits > 0, "WorkflowSemaphore.tryAcquire called with negative permits");
    if (currentPermits >= permits) {
      currentPermits -= permits;
      return true;
    }
    return false;
  }

  @Override
  public boolean tryAcquire(int permits, Duration timeout) {
    Preconditions.checkArgument(
        permits > 0, "WorkflowSemaphore.tryAcquire called with negative permits");
    boolean acquired =
        WorkflowInternal.await(
            timeout,
            "WorkflowSemaphore.tryAcquire",
            () -> {
              CancellationScope.throwCanceled();
              return currentPermits >= permits;
            });
    if (acquired) {
      currentPermits -= permits;
    }
    return acquired;
  }

  @Override
  public void release() {
    release(1);
  }

  @Override
  public void release(int permits) {
    assertNotReadOnly("WorkflowSemaphore.release");
    Preconditions.checkArgument(
        permits > 0, "WorkflowSemaphore.release called with negative permits");
    currentPermits += permits;
  }
}
