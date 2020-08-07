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

import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.uber.m3.tally.Scope;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder;
import io.temporal.internal.metrics.MetricsType;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public final class WorkflowExecutorCache {
  private final Scope metricsScope;
  private final LoadingCache<String, WorkflowExecutor> cache;
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
                  WorkflowExecutor entry = (WorkflowExecutor) e.getValue();
                  if (entry != null) {
                    entry.close();
                  }
                })
            .build(
                new CacheLoader<String, WorkflowExecutor>() {
                  @Override
                  public WorkflowExecutor load(String key) {
                    return null;
                  }
                });
  }

  public WorkflowExecutor getOrCreate(
      PollWorkflowTaskQueueResponseOrBuilder workflowTask,
      Scope metricsScope,
      Callable<WorkflowExecutor> workflowExecutorFn)
      throws Exception {
    String runId = workflowTask.getWorkflowExecution().getRunId();
    if (isFullHistory(workflowTask)) {
      invalidate(runId, metricsScope);
      return workflowExecutorFn.call();
    }

    WorkflowExecutor workflowExecutor = getForProcessing(runId, metricsScope);
    if (workflowExecutor != null) {
      return workflowExecutor;
    }
    return workflowExecutorFn.call();
  }

  private WorkflowExecutor getForProcessing(String runId, Scope metricsScope)
      throws ExecutionException {
    cacheLock.lock();
    try {
      WorkflowExecutor workflowExecutor = cache.get(runId);
      inProcessing.add(runId);
      metricsScope.counter(MetricsType.STICKY_CACHE_HIT).inc(1);
      return workflowExecutor;
    } catch (CacheLoader.InvalidCacheLoadException e) {
      // We don't have a default loader and don't want to have one. So it's ok to get null value.
      metricsScope.counter(MetricsType.STICKY_CACHE_MISS).inc(1);
      return null;
    } finally {
      cacheLock.unlock();
    }
  }

  void markProcessingDone(String runId) {
    cacheLock.lock();
    try {
      inProcessing.remove(runId);
    } finally {
      cacheLock.unlock();
    }
  }

  public void addToCache(String runId, WorkflowExecutor workflowExecutor) {
    cache.put(runId, workflowExecutor);
  }

  public boolean evictAnyNotInProcessing(String runId, Scope metricsScope) {
    cacheLock.lock();
    try {
      this.metricsScope.gauge(MetricsType.STICKY_CACHE_SIZE).update(size());
      for (String key : cache.asMap().keySet()) {
        if (!key.equals(runId) && !inProcessing.contains(key)) {
          cache.invalidate(key);
          this.metricsScope.gauge(MetricsType.STICKY_CACHE_SIZE).update(size());
          metricsScope.counter(MetricsType.STICKY_CACHE_THREAD_FORCED_EVICTION).inc(1);
          return true;
        }
      }

      return false;
    } finally {
      cacheLock.unlock();
    }
  }

  void invalidate(String runId, Scope metricsScope) {
    cacheLock.lock();
    try {
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

  private boolean isFullHistory(PollWorkflowTaskQueueResponseOrBuilder workflowTask) {
    return workflowTask.getHistory() != null
        && workflowTask.getHistory().getEventsCount() > 0
        && workflowTask.getHistory().getEvents(0).getEventId() == 1;
  }

  public void invalidateAll() {
    cache.invalidateAll();
  }
}
