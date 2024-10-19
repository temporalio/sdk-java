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

package io.temporal.worker;

import com.uber.m3.tally.Scope;
import io.temporal.internal.sync.WorkflowThreadExecutor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;

/**
 * This class implements a {@link WorkflowThreadExecutor} that report an amount of active tasks
 * using {@link MetricsType#WORKFLOW_ACTIVE_THREAD_COUNT} counter. Implemented as a custom
 * AtomicInteger instead of using {@link ThreadPoolExecutor#getActiveCount()} for performance
 * reasons. {@link ThreadPoolExecutor#getActiveCount()} take a pool-wide lock.
 */
class ActiveThreadReportingExecutor implements WorkflowThreadExecutor {
  private final ExecutorService workflowThreadPool;
  private final Scope metricsScope;
  private final AtomicInteger tasksInFlight = new AtomicInteger();

  ActiveThreadReportingExecutor(ExecutorService workflowThreadPool, Scope metricsScope) {
    this.workflowThreadPool = workflowThreadPool;
    this.metricsScope = metricsScope;
  }

  @Override
  public Future<?> submit(@Nonnull Runnable task) {
    return workflowThreadPool.submit(
        () -> {
          int tasksCount = tasksInFlight.incrementAndGet();
          metricsScope.gauge(MetricsType.WORKFLOW_ACTIVE_THREAD_COUNT).update(tasksCount);
          try {
            task.run();
          } finally {
            tasksCount = tasksInFlight.decrementAndGet();
            metricsScope.gauge(MetricsType.WORKFLOW_ACTIVE_THREAD_COUNT).update(tasksCount);
          }
        });
  }
}
