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

package io.temporal.internal.worker;

import static org.junit.Assert.*;

import java.util.concurrent.*;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkflowRunLockManagerTest {
  private static final Logger log = LoggerFactory.getLogger(WorkflowRunLockManagerTest.class);
  private final WorkflowRunLockManager runLockManager = new WorkflowRunLockManager();

  @Test
  public void lockAndUnlockTest() throws ExecutionException, InterruptedException {
    ExecutorService executor = Executors.newFixedThreadPool(4);
    ConcurrentLinkedQueue<String> finishedTasks = new ConcurrentLinkedQueue<>();
    Future<?> f1 = executor.submit(() -> finishedTasks.add(processTask("run1", 1)));
    Thread.sleep(100);
    Future<?> f3 = executor.submit(() -> finishedTasks.add(processTask("run1", 2)));
    Future<?> f2 = executor.submit(() -> finishedTasks.add(processTask("run2", 1)));
    Thread.sleep(100);
    Future<?> f4 = executor.submit(() -> finishedTasks.add(processTask("run1", 3)));

    f1.get();
    f2.get();
    f3.get();
    f4.get();

    log.info("All done.");
    assertEquals(0, runLockManager.totalLocks());
    String[] expectedTasks = {"run1.1", "run2.1", "run1.2", "run1.3"};
    String[] processedTasks = new String[4];
    assertArrayEquals(expectedTasks, finishedTasks.toArray(processedTasks));
  }

  private String processTask(String runId, int taskId) {
    try {
      log.info("trying to get a lock runId " + runId + " taskId " + taskId);
      boolean locked = runLockManager.tryLock(runId, 10, TimeUnit.SECONDS);
      assertTrue(locked);
    } catch (InterruptedException e) {
      fail("unexpected");
    }

    log.info("Got lock runId " + runId + " taskId " + taskId);
    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("interrupted");
    } finally {
      runLockManager.unlock(runId);
    }
    log.info("Finished processing runId " + runId + " taskId " + taskId);
    return runId + "." + taskId;
  }
}
