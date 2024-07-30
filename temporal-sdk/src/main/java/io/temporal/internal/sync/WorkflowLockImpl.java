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
import io.temporal.workflow.WorkflowLock;
import java.time.Duration;

class WorkflowLockImpl implements WorkflowLock {
  private boolean locked = false;

  @Override
  public void lock() {
    WorkflowInternal.await(
        "WorkflowLock.lock",
        () -> {
          CancellationScope.throwCanceled();
          return !locked;
        });
    locked = true;
  }

  @Override
  public boolean tryLock() {
    assertNotReadOnly("WorkflowLock.tryLock");
    if (!locked) {
      locked = true;
      return true;
    }
    return false;
  }

  @Override
  public boolean tryLock(Duration timeout) {
    boolean unlocked =
        WorkflowInternal.await(
            timeout,
            "WorkflowLock.tryLock",
            () -> {
              CancellationScope.throwCanceled();
              return !locked;
            });
    if (unlocked) {
      locked = true;
      return true;
    }
    return false;
  }

  @Override
  public void unlock() {
    assertNotReadOnly("WorkflowLock.unlock");
    Preconditions.checkState(locked, "WorkflowLock.unlock called when not locked");
    locked = false;
  }

  @Override
  public boolean isHeld() {
    return locked;
  }
}
