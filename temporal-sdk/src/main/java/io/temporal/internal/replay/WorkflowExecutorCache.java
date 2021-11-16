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

package io.temporal.internal.replay;

import static io.temporal.internal.common.WorkflowExecutionUtils.isFullHistory;

import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder;
import io.temporal.internal.metrics.MetricsType;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorkflowExecutorCache {
  private final Logger log = LoggerFactory.getLogger(WorkflowExecutorCache.class);

  private final Scope metricsScope;
  private final LoadingCache<String, WorkflowRunTaskHandler> cache;
  private final Lock cacheLock = new ReentrantLock();
  private final Set<String> inProcessing = new HashSet<>();

  public WorkflowExecutorCache(int workflowCacheSize, Scope scope) {
    Preconditions.checkArgument(workflowCacheSize > 0, "Max cache size must be greater than 0");
    this.metricsScope = Objects.requireNonNull(scope);
    this.cache =
        CacheBuilder.newBuilder()
            .maximumSize(workflowCacheSize)
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
  }

  public WorkflowRunTaskHandler getOrCreate(
      PollWorkflowTaskQueueResponseOrBuilder workflowTask,
      Scope metricsScope,
      Callable<WorkflowRunTaskHandler> workflowExecutorFn)
      throws Exception {
    WorkflowExecution execution = workflowTask.getWorkflowExecution();
    if (isFullHistory(workflowTask)) {
      invalidate(execution, metricsScope, "full history", null);
      log.trace(
          "New Workflow Executor {}-{} has been created for a full history run",
          execution.getWorkflowId(),
          execution.getRunId());
      return workflowExecutorFn.call();
    }

    WorkflowRunTaskHandler workflowRunTaskHandler = getForProcessing(execution, metricsScope);
    if (workflowRunTaskHandler != null) {
      return workflowRunTaskHandler;
    }

    log.trace(
        "Workflow Executor {}-{} wasn't found in cache and a new executor has been created",
        execution.getWorkflowId(),
        execution.getRunId());
    return workflowExecutorFn.call();
  }

  private WorkflowRunTaskHandler getForProcessing(
      WorkflowExecution workflowExecution, Scope metricsScope) throws ExecutionException {
    String runId = workflowExecution.getRunId();
    cacheLock.lock();
    try {
      WorkflowRunTaskHandler workflowRunTaskHandler = cache.get(runId);
      inProcessing.add(runId);
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
    } finally {
      cacheLock.unlock();
    }
  }

  void markProcessingDone(WorkflowExecution workflowExecution) {
    cacheLock.lock();
    try {
      inProcessing.remove(workflowExecution.getRunId());
      log.trace(
          "Workflow Execution {}-{} has been marked as not in-progress",
          workflowExecution.getWorkflowId(),
          workflowExecution.getRunId());
    } finally {
      cacheLock.unlock();
    }
  }

  public void addToCache(
      WorkflowExecution workflowExecution, WorkflowRunTaskHandler workflowRunTaskHandler) {
    cache.put(workflowExecution.getRunId(), workflowRunTaskHandler);
    log.trace(
        "Workflow Execution {}-{} has been added to cache",
        workflowExecution.getWorkflowId(),
        workflowExecution.getRunId());
  }

  public boolean evictAnyNotInProcessing(WorkflowExecution inFavorOfExecution, Scope metricsScope) {
    cacheLock.lock();
    try {
      this.metricsScope.gauge(MetricsType.STICKY_CACHE_SIZE).update(size());
      String inFavorOfRunId = inFavorOfExecution.getRunId();
      for (String key : cache.asMap().keySet()) {
        if (!key.equals(inFavorOfRunId) && !inProcessing.contains(key)) {
          log.trace(
              "Workflow Execution {}-{} caused eviction of Workflow Execution with runId {}",
              inFavorOfExecution.getWorkflowId(),
              inFavorOfRunId,
              key);
          cache.invalidate(key);
          this.metricsScope.gauge(MetricsType.STICKY_CACHE_SIZE).update(size());
          metricsScope.counter(MetricsType.STICKY_CACHE_THREAD_FORCED_EVICTION).inc(1);
          return true;
        }
      }

      log.trace(
          "Failed to evict from Workflow Execution cache, cache size is {}, inProcessing collection size is {}",
          cache.size(),
          inProcessing.size());
      return false;
    } finally {
      cacheLock.unlock();
    }
  }

  public void invalidate(
      WorkflowExecution execution, Scope metricsScope, String reason, Throwable cause) {
    cacheLock.lock();
    try {
      String runId = execution.getRunId();
      log.trace(
          "Invalidating {}-{} because of {}, value is present in the cache: {}",
          execution.getWorkflowId(),
          runId,
          reason,
          cache.getIfPresent(runId),
          cause);
      cache.invalidate(runId);
      inProcessing.remove(runId);
      metricsScope.counter(MetricsType.STICKY_CACHE_TOTAL_FORCED_EVICTION).inc(1);
    } finally {
      cacheLock.unlock();
    }
  }

  public long size() {
    return cache.size();
  }

  public void invalidateAll() {
    cache.invalidateAll();
  }
}
