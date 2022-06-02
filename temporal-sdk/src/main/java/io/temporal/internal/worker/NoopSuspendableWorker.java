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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Helper class that is used instead of null for non initialized worker. This eliminates needs for
 * null checks when calling into it.
 */
class NoopSuspendableWorker implements SuspendableWorker {

  private final AtomicBoolean shutdown = new AtomicBoolean();

  @Override
  public boolean isShutdown() {
    return shutdown.get();
  }

  @Override
  public boolean isTerminated() {
    return shutdown.get();
  }

  @Override
  public CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptTasks) {
    shutdown.set(true);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {}

  @Override
  public void start() {
    throw new IllegalStateException("Non startable");
  }

  @Override
  public boolean isStarted() {
    return false;
  }

  @Override
  public void suspendPolling() {}

  @Override
  public void resumePolling() {}

  @Override
  public boolean isSuspended() {
    return true;
  }
}
