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

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowOptions;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowInternalSemaphoreTest {
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
              WorkflowSemaphore s1 = WorkflowInternal.newWorkflowSemaphore(100);
              trace.add("root begin");
              s1.acquire(10);
              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread1 begin");
                        s1.acquire(50);
                        trace.add("thread1 acquire 50 success");
                        s1.acquire(50);
                        trace.add("thread1 acquire 50 success");
                        s1.release(100);
                        trace.add("thread1 release 100 success");
                      },
                      false)
                  .start();
              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread2 begin");
                        s1.release(10);
                        trace.add("thread2 release 10 success");
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
          "thread1 acquire 50 success",
          "thread2 begin",
          "thread2 release 10 success",
          "thread1 acquire 50 success",
          "thread1 release 100 success",
        };
    trace.setExpected(expected);
    r.close();
  }

  @Test
  public void testSemaphoreReleaseWithoutAcquire() {
    DeterministicRunner r =
        DeterministicRunner.newRunner(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              WorkflowSemaphore s1 = WorkflowInternal.newWorkflowSemaphore(0);
              trace.add("root begin");
              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread1 begin");
                        s1.acquire(50);
                        trace.add("thread1 acquire 50 success");
                        s1.acquire(50);
                        trace.add("thread1 acquire 50 success");
                        s1.release(100);
                        trace.add("thread1 release 100 success");
                      },
                      false)
                  .start();
              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread2 begin");
                        // There is no requirement to acquire before release
                        s1.release(100);
                        trace.add("thread2 release 100 success");
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
          "thread2 begin",
          "thread2 release 100 success",
          "thread1 acquire 50 success",
          "thread1 acquire 50 success",
          "thread1 release 100 success",
        };
    trace.setExpected(expected);
    r.close();
  }

  @Test
  public void testSemaphoreAcquireCanceled() {
    DeterministicRunner r =
        DeterministicRunner.newRunner(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              WorkflowSemaphore s = WorkflowInternal.newWorkflowSemaphore(100);
              trace.add("root begin");
              s.acquire(100);
              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread1 begin");
                        try {
                          s.acquire();
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
  public void testTryAcquire() {
    DeterministicRunner r =
        DeterministicRunner.newRunner(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              WorkflowSemaphore s = WorkflowInternal.newWorkflowSemaphore(100);
              trace.add("root begin");
              s.acquire(100);
              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread1 begin");
                        if (s.tryAcquire()) {
                          trace.add("thread1 tryAcquire success");
                        } else {
                          trace.add("thread1 tryAcquire failure");
                        }
                        trace.add("thread1 done");
                        s.release(100);
                      },
                      false)
                  .start();
              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread2 begin");
                        if (s.tryAcquire()) {
                          trace.add("thread2 tryAcquire success");
                        } else {
                          trace.add("thread2 tryAcquire failure");
                        }
                        trace.add("thread2 done");
                        s.release();
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
          "thread1 tryAcquire failure",
          "thread1 done",
          "thread2 begin",
          "thread2 tryAcquire success",
          "thread2 done",
        };
    trace.setExpected(expected);
  }

  @WorkflowInterface
  public interface WorkflowSemaphoreTestWorkflow {
    @WorkflowMethod
    List<String> test();
  }

  public static class TestAcquireTimeout implements WorkflowSemaphoreTestWorkflow {
    @Override
    public List<String> test() {
      List<String> trace = new ArrayList<>();
      WorkflowSemaphore s = WorkflowInternal.newWorkflowSemaphore(100);
      trace.add("root begin");
      trace.add("tryAcquire " + s.tryAcquire(100));
      trace.add("tryAcquire " + s.tryAcquire(100));
      WorkflowThread.newThread(
              () -> {
                trace.add("thread1 begin");
                Workflow.sleep(2000);
                s.release(100);
                trace.add("thread1 release");
              },
              false)
          .start();
      // Try to lock again before the above thread unlocks
      trace.add("tryAcquire with timeout " + s.tryAcquire(100, Duration.ofMillis(1000)));
      // Try to lock again after the above thread unlocks
      trace.add("tryAcquire with timeout " + s.tryAcquire(100, Duration.ofMillis(2000)));
      trace.add("root done");
      return trace;
    }
  }

  @Test
  public void testAcquireTimeout() {
    TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance();
    try {
      String testTaskQueue = "testTaskQueue";
      Worker worker = testEnv.newWorker(testTaskQueue);
      worker.registerWorkflowImplementationTypes(TestAcquireTimeout.class);
      testEnv.start();
      WorkflowSemaphoreTestWorkflow workflow =
          testEnv
              .getWorkflowClient()
              .newWorkflowStub(
                  WorkflowSemaphoreTestWorkflow.class,
                  WorkflowOptions.newBuilder().setTaskQueue(testTaskQueue).build());
      List<String> trace = workflow.test();
      List<String> expected =
          Arrays.asList(
              "root begin",
              "tryAcquire true",
              "tryAcquire false",
              "thread1 begin",
              "tryAcquire with timeout false",
              "thread1 release",
              "tryAcquire with timeout true",
              "root done");
      assertEquals(expected, trace);
    } finally {
      testEnv.close();
    }
  }

  public static class TestTryAcquireCancelled implements WorkflowSemaphoreTestWorkflow {
    @Override
    public List<String> test() {
      List<String> trace = new ArrayList<>();
      WorkflowSemaphore s = WorkflowInternal.newWorkflowSemaphore(100);
      trace.add("root begin");
      trace.add("tryAcquire " + s.tryAcquire(100));
      WorkflowThread t1 =
          WorkflowThread.newThread(
              () -> {
                trace.add("thread1 begin");
                try {
                  trace.add("tryAcquire with timeout " + s.tryAcquire(Duration.ofMillis(2000)));
                } catch (CanceledFailure e) {
                  trace.add("thread1 CanceledFailure");
                }
                trace.add("thread1 done");
              },
              false);
      t1.start();
      Workflow.sleep(1000);
      t1.cancel();
      trace.add("tryAcquire with timeout " + s.tryAcquire(Duration.ofMillis(2000)));
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
      worker.registerWorkflowImplementationTypes(TestTryAcquireCancelled.class);
      testEnv.start();
      WorkflowSemaphoreTestWorkflow workflow =
          testEnv
              .getWorkflowClient()
              .newWorkflowStub(
                  WorkflowSemaphoreTestWorkflow.class,
                  WorkflowOptions.newBuilder().setTaskQueue(testTaskQueue).build());
      List<String> trace = workflow.test();
      List<String> expected =
          Arrays.asList(
              "root begin",
              "tryAcquire true",
              "thread1 begin",
              "thread1 CanceledFailure",
              "thread1 done",
              "tryAcquire with timeout false",
              "root done");
      assertEquals(expected, trace);
    } finally {
      testEnv.close();
    }
  }
}
