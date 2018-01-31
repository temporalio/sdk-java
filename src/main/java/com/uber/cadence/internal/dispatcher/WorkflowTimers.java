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
package com.uber.cadence.internal.dispatcher;

import com.uber.cadence.workflow.WorkflowFuture;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Helper class for timers.
 * Not thread safe.
 */
class WorkflowTimers {

    /**
     * Timers that fire at the same time.
     */
    private static class Timers {

        private final Set<WorkflowFuture<Void>> results = new HashSet<>();

        public void addTimer(WorkflowFuture<Void> result) {
            results.add(result);
            // Remove timer on cancellation
            result.handle((r, failure) -> {
                if (failure != null) {
                    results.remove(result);
                    throw failure;
                }
                return r;
            });
        }

        public void fire() {
            for (WorkflowFuture<Void> t : results) {
                t.complete(null);
            }
        }
    }

    /**
     * Timers sorted by fire time.
     */
    private final SortedMap<Long, Timers> timers = new TreeMap<>();

    public void addTimer(long fireTime, WorkflowFuture<Void> result) {
        Timers t = timers.get(fireTime);
        if (t == null) {
            t = new Timers();
            timers.put(fireTime, t);
        }
        t.addTimer(result);
    }

    /**
     * @return true if any timer fired
     */
    public boolean fireTimers(long currentTime) {
        List<Long> toDelete = new ArrayList<>();
        for (Map.Entry<Long, Timers> pair : timers.entrySet()) {
            if (pair.getKey() > currentTime) {
                break;
            }
            pair.getValue().fire();
            toDelete.add(pair.getKey());
        }
        for (Long key : toDelete) {
            timers.remove(key);
        }
        return !toDelete.isEmpty();
    }

    public long getNextFireTime() {
        if (timers.isEmpty()) {
            return 0;
        }
        return timers.firstKey();
    }
}
