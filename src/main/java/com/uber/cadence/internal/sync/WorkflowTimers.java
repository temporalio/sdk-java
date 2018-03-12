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

package com.uber.cadence.internal.sync;

import com.uber.cadence.workflow.CompletablePromise;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/** Helper class for timers. Not thread safe. */
class WorkflowTimers {

  /** Timers that fire at the same time. */
  private static class Timers {

    private final Set<CompletablePromise<Void>> results = new HashSet<>();

    private final long fireTime;

    private Timers(long fireTime) {
      this.fireTime = fireTime;
    }

    void addTimer(CompletablePromise<Void> result) {
      results.add(result);
      // Remove timer on cancellation
      result.handle(
          (r, failure) -> {
            if (failure != null) {
              results.remove(result);
              throw failure;
            }
            return r;
          });
    }

    void fire() {
      for (CompletablePromise<Void> t : results) {
        t.complete(null);
      }
    }

    public void remove(CompletablePromise<Void> result) {
      results.remove(result);
    }

    public boolean isEmpty() {
      return results.isEmpty();
    }
  }

  /** Timers sorted by fire time. */
  private final SortedMap<Long, Timers> timers = new TreeMap<>();

  public void addTimer(long fireTime, CompletablePromise<Void> result) {
    Timers t = timers.get(fireTime);
    if (t == null) {
      t = new Timers(fireTime);
      timers.put(fireTime, t);
    }
    t.addTimer(result);
  }

  public void removeTimer(long fireTime, CompletablePromise<Void> result) {
    Timers t = timers.get(fireTime);
    if (t == null) {
      throw new Error("Unknown timer");
    }
    t.remove(result);
    if (t.isEmpty()) {
      timers.remove(fireTime);
    }
  }

  public boolean hasTimersToFire(long currentTime) {
    return !timers.isEmpty() && timers.firstKey() <= currentTime;
  }

  /** @return true if any timer fired */
  public void fireTimers(long currentTime) {
    boolean fired = false;
    boolean newTimersAdded;
    do {
      List<Timers> toFire = new ArrayList<>();
      for (Map.Entry<Long, Timers> pair : timers.entrySet()) {
        if (pair.getKey() > currentTime) {
          break;
        }
        toFire.add(pair.getValue());
      }
      int beforeSize = timers.size() - toFire.size();
      for (Timers t : toFire) {
        t.fire();
        timers.remove(t.fireTime);
      }
      newTimersAdded = timers.size() > beforeSize;
      fired = fired || !toFire.isEmpty();
    } while (newTimersAdded);
  }

  public long getNextFireTime() {
    if (timers.isEmpty()) {
      return 0;
    }
    return timers.firstKey();
  }
}
