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

package io.temporal.internal.sync;

import static org.junit.Assert.*;

import io.temporal.client.WorkflowOptions;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowInternalLockTest {
  @Rule public final Tracer trace = new Tracer();

  private static ExecutorService threadPool;

  @BeforeClass
  public static void beforeClass() {
    threadPool = new ThreadPoolExecutor(1, 1000, 1, TimeUnit.SECONDS, new SynchronousQueue<>());
  }

  @AfterClass
  public static void afterClass() {
    threadPool.shutdown();
  }

  @Test
  public void testThreadInterleaving() {
    DeterministicRunner r =
        DeterministicRunner.newRunner(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              WorkflowLock l1 = WorkflowInternal.newWorkflowLock();
              WorkflowLock l2 = WorkflowInternal.newWorkflowLock();
              trace.add("root begin");
              l2.lock();
              trace.add("l1.isHeld " + l1.isHeld());
              trace.add("l2.isHeld " + l2.isHeld());
              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread1 begin");
                        l1.lock();
                        trace.add("thread1 lock 1 success");
                        l2.lock();
                        trace.add("thread1 lock 2 success");
                        l1.unlock();
                        trace.add("thread1 unlock 1 success");
                      },
                      false)
                  .start();
              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread2 begin");
                        l2.unlock();
                        trace.add("thread2 unlock 2 success");
                        l1.lock();
                        trace.add("thread2 lock 1 success");
                        l1.unlock();
                        trace.add("thread2 unlock 1 success");
                      },
                      false)
                  .start();
              trace.add("root done");
            });
    r.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    String[] expected =
        new String[] {
          "root begin",
          "l1.isHeld false",
          "l2.isHeld true",
          "root done",
          "thread1 begin",
          "thread1 lock 1 success",
          "thread2 begin",
          "thread2 unlock 2 success",
          "thread1 lock 2 success",
          "thread1 unlock 1 success",
          "thread2 lock 1 success",
          "thread2 unlock 1 success",
        };
    trace.setExpected(expected);
    r.close();
  }

  @Test
  public void testLockCanceled() {
    DeterministicRunner r =
        DeterministicRunner.newRunner(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              WorkflowLock l = WorkflowInternal.newWorkflowLock();
              trace.add("root begin");
              l.lock();
              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread1 begin");
                        try {
                          l.lock();
                        } catch (CanceledFailure e) {
                          trace.add("thread1 CanceledFailure");
                        }
                        trace.add("thread1 done");
                      },
                      false)
                  .start();
              trace.add("root done");
            });
    r.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    r.cancel("test");
    r.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);

    String[] expected =
        new String[] {
          "root begin", "root done", "thread1 begin", "thread1 CanceledFailure", "thread1 done",
        };
    trace.setExpected(expected);
  }

  @Test
  public void testTryLock() {
    DeterministicRunner r =
        DeterministicRunner.newRunner(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              WorkflowLock l = WorkflowInternal.newWorkflowLock();
              trace.add("root begin");
              l.lock();
              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread1 begin");
                        if (l.tryLock()) {
                          trace.add("thread1 tryLock success");
                        } else {
                          trace.add("thread1 tryLock failure");
                        }
                        trace.add("thread1 done");
                        l.unlock();
                      },
                      false)
                  .start();
              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread2 begin");
                        if (l.tryLock()) {
                          trace.add("thread2 tryLock success");
                        } else {
                          trace.add("thread2 tryLock failure");
                        }
                        trace.add("thread2 done");
                        l.unlock();
                      },
                      false)
                  .start();
              trace.add("root done");
            });
    r.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);

    String[] expected =
        new String[] {
          "root begin",
          "root done",
          "thread1 begin",
          "thread1 tryLock failure",
          "thread1 done",
          "thread2 begin",
          "thread2 tryLock success",
          "thread2 done",
        };
    trace.setExpected(expected);
  }

  @WorkflowInterface
  public interface WorkflowLockTestWorkflow {
    @WorkflowMethod
    List<String> test();
  }

  public static class TestLockTimeout implements WorkflowLockTestWorkflow {
    @Override
    public List<String> test() {
      List<String> trace = new ArrayList<>();
      WorkflowLock l = WorkflowInternal.newWorkflowLock();
      trace.add("root begin");
      trace.add("tryLock " + l.tryLock());
      trace.add("tryLock " + l.tryLock());
      WorkflowThread.newThread(
              () -> {
                trace.add("thread1 begin");
                Workflow.sleep(2000);
                l.unlock();
                trace.add("thread1 unlock");
              },
              false)
          .start();
      // Try to lock again before the above thread unlocks
      trace.add("tryLock with timeout " + l.tryLock(Duration.ofMillis(1000)));
      // Try to lock again after the above thread unlocks
      trace.add("tryLock with timeout " + l.tryLock(Duration.ofMillis(2000)));
      trace.add("root done");
      return trace;
    }
  }

  @Test
  public void testLockTimeout() {
    TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance();
    try {
      String testTaskQueue = "testTaskQueue";
      Worker worker = testEnv.newWorker(testTaskQueue);
      worker.registerWorkflowImplementationTypes(TestLockTimeout.class);
      testEnv.start();
      WorkflowLockTestWorkflow workflow =
          testEnv
              .getWorkflowClient()
              .newWorkflowStub(
                  WorkflowLockTestWorkflow.class,
                  WorkflowOptions.newBuilder().setTaskQueue(testTaskQueue).build());
      List<String> trace = workflow.test();
      List<String> expected =
          Arrays.asList(
              "root begin",
              "tryLock true",
              "tryLock false",
              "thread1 begin",
              "tryLock with timeout false",
              "thread1 unlock",
              "tryLock with timeout true",
              "root done");
      assertEquals(expected, trace);
    } finally {
      testEnv.close();
    }
  }

  public static class TestTryLockCancelled implements WorkflowLockTestWorkflow {
    @Override
    public List<String> test() {
      List<String> trace = new ArrayList<>();
      WorkflowLock l = WorkflowInternal.newWorkflowLock();
      trace.add("root begin");
      trace.add("tryLock " + l.tryLock());
      WorkflowThread t1 =
          WorkflowThread.newThread(
              () -> {
                trace.add("thread1 begin");
                try {
                  trace.add("tryLock with timeout " + l.tryLock(Duration.ofMillis(2000)));
                } catch (CanceledFailure e) {
                  trace.add("thread1 CanceledFailure");
                }
                trace.add("thread1 done");
              },
              false);
      t1.start();
      Workflow.sleep(1000);
      t1.cancel();
      trace.add("tryLock with timeout " + l.tryLock(Duration.ofMillis(2000)));
      trace.add("root done");
      return trace;
    }
  }

  @Test
  public void tesTryLockCancelled() {
    TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance();
    try {
      String testTaskQueue = "testTaskQueue";
      Worker worker = testEnv.newWorker(testTaskQueue);
      worker.registerWorkflowImplementationTypes(TestTryLockCancelled.class);
      testEnv.start();
      WorkflowLockTestWorkflow workflow =
          testEnv
              .getWorkflowClient()
              .newWorkflowStub(
                  WorkflowLockTestWorkflow.class,
                  WorkflowOptions.newBuilder().setTaskQueue(testTaskQueue).build());
      List<String> trace = workflow.test();
      List<String> expected =
          Arrays.asList(
              "root begin",
              "tryLock true",
              "thread1 begin",
              "thread1 CanceledFailure",
              "thread1 done",
              "tryLock with timeout false",
              "root done");
      assertEquals(expected, trace);
    } finally {
      testEnv.close();
    }
  }
}
