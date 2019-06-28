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

package com.uber.cadence.internal.worker;

import java.util.HashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

final class WorkflowRunLockManager {

  private static class CountableLock {
    private final Lock lock = new ReentrantLock();
    private int count = 1;

    void incrementCount() {
      count++;
    }

    void decrementCount() {
      count--;
    }

    int getCount() {
      return count;
    }

    Lock getLock() {
      return lock;
    }
  }

  private final Lock mapLock = new ReentrantLock();
  private final HashMap<String, CountableLock> perRunLock = new HashMap<>();

  /**
   * This method returns a lock that can be used to serialize decision task processing for a
   * particular workflow run. This is used to make sure that query tasks and real decision tasks are
   * serialized when sticky is on.
   *
   * @param runId
   * @return a lock to be used during decision task processing
   */
  Lock getLockForLocking(String runId) {
    mapLock.lock();

    try {
      CountableLock cl = perRunLock.get(runId);
      if (cl == null) {
        cl = new CountableLock();
        perRunLock.put(runId, cl);
      } else {
        cl.incrementCount();
      }

      return cl.getLock();
    } finally {
      mapLock.unlock();
    }
  }

  void unlock(String runId) {
    mapLock.lock();

    try {
      CountableLock cl = perRunLock.get(runId);
      if (cl == null) {
        throw new RuntimeException("lock for run " + runId + " does not exist.");
      }

      cl.decrementCount();
      if (cl.getCount() == 0) {
        perRunLock.remove(runId);
      }

      cl.getLock().unlock();
    } finally {
      mapLock.unlock();
    }
  }

  int totalLocks() {
    mapLock.lock();

    try {
      return perRunLock.size();
    } finally {
      mapLock.unlock();
    }
  }
}
