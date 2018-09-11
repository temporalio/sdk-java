/*
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

package com.uber.cadence.internal.replay;

import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.Weigher;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.internal.common.ThrowableFunc1;
import java.util.UUID;

public final class DeciderCache {
  private final String evictionEntryId = UUID.randomUUID().toString();
  private final int maxCacheSize;
  private LoadingCache<String, WeightedCacheEntry<Decider>> cache;

  public DeciderCache(int maxCacheSize) {
    Preconditions.checkArgument(maxCacheSize > 0, "Max cache size must be greater than 0");
    this.maxCacheSize = maxCacheSize;
    this.cache =
        CacheBuilder.newBuilder()
            .maximumWeight(maxCacheSize)
            .weigher(
                (Weigher<String, WeightedCacheEntry<Decider>>) (key, value) -> value.getWeight())
            .removalListener(
                e -> {
                  Decider entry = e.getValue().entry;
                  if (entry != null) {
                    entry.close();
                  }
                })
            .build(
                new CacheLoader<String, WeightedCacheEntry<Decider>>() {
                  @Override
                  public WeightedCacheEntry<Decider> load(String key) {
                    return null;
                  }
                });
  }

  public Decider getOrCreate(
      PollForDecisionTaskResponse decisionTask,
      ThrowableFunc1<PollForDecisionTaskResponse, Decider, Exception> createReplayDecider)
      throws Exception {
    String runId = decisionTask.getWorkflowExecution().getRunId();
    if (isFullHistory(decisionTask)) {
      cache.invalidate(runId);
      return cache.get(
              runId, () -> new WeightedCacheEntry<>(createReplayDecider.apply(decisionTask), 1))
          .entry;
    }
    return getUnchecked(runId);
  }

  public Decider getUnchecked(String runId) throws Exception {
    try {
      return cache.getUnchecked(runId).entry;
    } catch (CacheLoader.InvalidCacheLoadException e) {
      throw new EvictedException(runId);
    }
  }

  public void evictNext() {
    int remainingSpace = (int) (maxCacheSize - cache.size());
    // Force eviction to happen
    cache.put(evictionEntryId, new WeightedCacheEntry<>(null, remainingSpace + 1));
    cache.invalidate(evictionEntryId);
  }

  public void invalidate(PollForDecisionTaskResponse decisionTask) {
    String runId = decisionTask.getWorkflowExecution().getRunId();
    invalidate(runId);
  }

  public void invalidate(String runId) {
    cache.invalidate(runId);
  }

  public long size() {
    return cache.size();
  }

  private boolean isFullHistory(PollForDecisionTaskResponse decisionTask) {
    return decisionTask.getHistory() != null
        && decisionTask.getHistory().getEventsSize() > 0
        && decisionTask.history.events.get(0).getEventId() == 1;
  }

  public void invalidateAll() {
    cache.invalidateAll();
  }

  // Used for eviction
  private static class WeightedCacheEntry<T> {
    private T entry;
    private int weight;

    private WeightedCacheEntry(T entry, int weight) {
      this.entry = entry;
      this.weight = weight;
    }

    public T getEntry() {
      return entry;
    }

    public int getWeight() {
      return weight;
    }
  }

  public static class EvictedException extends Exception {

    public EvictedException(String runId) {
      super(String.format("cache was evicted for the decisionTask. RunId: %s", runId));
    }
  }
}
