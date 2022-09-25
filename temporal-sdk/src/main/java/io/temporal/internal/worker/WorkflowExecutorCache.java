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

import static io.temporal.internal.common.WorkflowExecutionUtils.isFullHistory;

import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder;
import io.temporal.internal.replay.WorkflowRunTaskHandler;
import io.temporal.worker.MetricsType;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ThreadSafe
public final class WorkflowExecutorCache {
  private final Logger log = LoggerFactory.getLogger(WorkflowExecutorCache.class);
  private final WorkflowRunLockManager runLockManager;
  private final LoadingCache<String, WorkflowRunTaskHandler> cache;
  private final Scope metricsScope;

  public WorkflowExecutorCache(
      int workflowCacheSize, WorkflowRunLockManager runLockManager, Scope scope) {
    Preconditions.checkArgument(workflowCacheSize > 0, "Max cache size must be greater than 0");
    this.runLockManager = runLockManager;
    this.cache =
        CacheBuilder.newBuilder()
            .maximumSize(workflowCacheSize)
            // TODO this number is taken out of the blue.
            //  This number should be calculated based on the number of all workers workflow task
            //  processors.
            .concurrencyLevel(128)
            .removalListener(
                e -> {
                  WorkflowRunTaskHandler entry = (WorkflowRunTaskHandler) e.getValue();
                  if (entry != null) {
                    try {
                      log.trace(
                          "Closing workflow execution for runId {}, cause {}",
                          e.getKey(),
                          e.getCause());
                      entry.close();
                      log.trace("Workflow execution for runId {} closed", e);
                    } catch (Throwable t) {
                      log.error("Workflow execution closure failed with an exception", t);
                      throw t;
                    }
                  }
                })
            .build(
                new CacheLoader<String, WorkflowRunTaskHandler>() {
                  @Override
                  public WorkflowRunTaskHandler load(String key) {
                    return null;
                  }
                });
    this.metricsScope = Objects.requireNonNull(scope);
    this.metricsScope.gauge(MetricsType.STICKY_CACHE_SIZE).update(size());
  }

  public WorkflowRunTaskHandler getOrCreate(
      PollWorkflowTaskQueueResponseOrBuilder workflowTask,
      Scope workflowTypeScope,
      Callable<WorkflowRunTaskHandler> workflowExecutorFn)
      throws Exception {
    WorkflowExecution execution = workflowTask.getWorkflowExecution();
    if (isFullHistory(workflowTask)) {
      // no need to call a full-blown #invalidate, because we don't need to unmark from processing
      // yet
      cache.invalidate(execution.getRunId());
      metricsScope.counter(MetricsType.STICKY_CACHE_TOTAL_FORCED_EVICTION).inc(1);

      log.trace(
          "New Workflow Executor {}-{} has been created for a full history run",
          execution.getWorkflowId(),
          execution.getRunId());
      return workflowExecutorFn.call();
    }

    WorkflowRunTaskHandler workflowRunTaskHandler = getForProcessing(execution, workflowTypeScope);
    if (workflowRunTaskHandler != null) {
      return workflowRunTaskHandler;
    }

    log.trace(
        "Workflow Executor {}-{} wasn't found in cache and a new executor has been created",
        execution.getWorkflowId(),
        execution.getRunId());
    return workflowExecutorFn.call();
  }

  public WorkflowRunTaskHandler getForProcessing(
      WorkflowExecution workflowExecution, Scope metricsScope) throws ExecutionException {
    String runId = workflowExecution.getRunId();
    try {
      WorkflowRunTaskHandler workflowRunTaskHandler = cache.get(runId);
      log.trace(
          "Workflow Execution {}-{} has been marked as in-progress",
          workflowExecution.getWorkflowId(),
          workflowExecution.getRunId());
      metricsScope.counter(MetricsType.STICKY_CACHE_HIT).inc(1);
      return workflowRunTaskHandler;
    } catch (CacheLoader.InvalidCacheLoadException e) {
      // We don't have a default loader and don't want to have one. So it's ok to get null value.
      metricsScope.counter(MetricsType.STICKY_CACHE_MISS).inc(1);
      return null;
    }
  }

  public void addToCache(
      WorkflowExecution workflowExecution, WorkflowRunTaskHandler workflowRunTaskHandler) {
    cache.put(workflowExecution.getRunId(), workflowRunTaskHandler);
    log.trace(
        "Workflow Execution {}-{} has been added to cache",
        workflowExecution.getWorkflowId(),
        workflowExecution.getRunId());
    this.metricsScope.gauge(MetricsType.STICKY_CACHE_SIZE).update(size());
  }

  public boolean evictAnyNotInProcessing(WorkflowExecution inFavorOfExecution, Scope metricsScope) {
    try {
      String inFavorOfRunId = inFavorOfExecution.getRunId();
      for (String key : cache.asMap().keySet()) {
        if (key.equals(inFavorOfRunId)) continue;
        boolean locked = runLockManager.tryLock(key);
        // if we were able to take a lock here, it means that the workflow is not in processing
        // currently on workers of this WorkerFactory and can be evicted
        if (locked) {
          try {
            log.trace(
                "Workflow Execution {}-{} caused eviction of Workflow Execution with runId {}",
                inFavorOfExecution.getWorkflowId(),
                inFavorOfRunId,
                key);
            cache.invalidate(key);
            metricsScope.counter(MetricsType.STICKY_CACHE_THREAD_FORCED_EVICTION).inc(1);
            metricsScope.counter(MetricsType.STICKY_CACHE_TOTAL_FORCED_EVICTION).inc(1);
            return true;
          } finally {
            runLockManager.unlock(key);
          }
        }
      }

      log.trace("Failed to evict from Workflow Execution cache, cache size is {}", cache.size());
      return false;
    } finally {
      this.metricsScope.gauge(MetricsType.STICKY_CACHE_SIZE).update(size());
    }
  }

  public void invalidate(
      WorkflowExecution execution, Scope workflowTypeScope, String reason, Throwable cause) {
    try {
      String runId = execution.getRunId();
      if (log.isTraceEnabled()) {
        log.trace(
            "Invalidating {}-{} because of '{}', value is present in the cache: {}",
            execution.getWorkflowId(),
            runId,
            reason,
            cache.getIfPresent(runId),
            cause);
      }
      cache.invalidate(runId);
      workflowTypeScope.counter(MetricsType.STICKY_CACHE_TOTAL_FORCED_EVICTION).inc(1);
    } finally {
      this.metricsScope.gauge(MetricsType.STICKY_CACHE_SIZE).update(size());
    }
  }

  public long size() {
    return cache.size();
  }

  public void invalidateAll() {
    cache.invalidateAll();
    metricsScope.gauge(MetricsType.STICKY_CACHE_SIZE).update(size());
  }
}
