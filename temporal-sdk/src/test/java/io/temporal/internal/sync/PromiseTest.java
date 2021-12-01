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

package io.temporal.internal.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.temporal.client.WorkflowOptions;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IllegalFormatCodePointException;
import java.util.List;
import java.util.concurrent.*;
import org.junit.*;

public class PromiseTest {

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
  public void testFailure() {
    DeterministicRunner r =
        DeterministicRunner.newRunner(
            threadPool,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              CompletablePromise<Boolean> f = Workflow.newPromise();
              trace.add("root begin");
              WorkflowThread.newThread(
                      () -> f.completeExceptionally(new IllegalArgumentException("foo")), false)
                  .start();
              WorkflowThread.newThread(
                      () -> {
                        try {
                          f.get();
                          trace.add("thread1 get success");
                          fail("failure expected");
                        } catch (Exception e) {
                          assertEquals(IllegalArgumentException.class, e.getClass());
                          trace.add("thread1 get failure");
                        }
                      },
                      false)
                  .start();
              trace.add("root done");
            });
    r.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    String[] expected =
        new String[] {
          "root begin", "root done", "thread1 get failure",
        };
    trace.setExpected(expected);
  }

  @Test
  public void testGet() {
    DeterministicRunner r =
        DeterministicRunner.newRunner(
            threadPool,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              CompletablePromise<String> f = Workflow.newPromise();
              trace.add("root begin");
              WorkflowThread.newThread(() -> f.complete("thread1"), false).start();
              trace.add(f.get());
              trace.add("root done");
            });
    r.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    String[] expected =
        new String[] {
          "root begin", "thread1", "root done",
        };
    trace.setExpected(expected);
  }

  @Test
  public void testCancellableGet() {
    DeterministicRunner r =
        DeterministicRunner.newRunner(
            threadPool,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              CompletablePromise<String> f = Workflow.newPromise();
              trace.add("root begin");
              WorkflowThread.newThread(() -> f.complete("thread1"), false).start();
              trace.add(f.cancellableGet());
              trace.add("root done");
            });
    r.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    String[] expected =
        new String[] {
          "root begin", "thread1", "root done",
        };
    trace.setExpected(expected);
  }

  @Test
  public void testCancellableGetCancellation() {
    DeterministicRunner r =
        DeterministicRunner.newRunner(
            threadPool,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              CompletablePromise<String> f = Workflow.newPromise();
              trace.add("root begin");
              try {
                f.cancellableGet();
              } catch (CanceledFailure e) {
                trace.add("root CanceledException");
              }
              trace.add("root done");
            });
    r.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    r.cancel("test");
    r.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    String[] expected =
        new String[] {
          "root begin", "root CanceledException", "root done",
        };
    trace.setExpected(expected);
  }

  @WorkflowInterface
  public interface PromiseTestWorkflow {
    @WorkflowMethod
    List<String> test();
  }

  public static class TestGetTimeout implements PromiseTestWorkflow {

    @Override
    public List<String> test() {
      List<String> trace = new ArrayList<>();
      CompletablePromise<String> f = Workflow.newPromise();
      trace.add("thread1 begin");
      try {
        assertEquals("bar", f.get(10, TimeUnit.SECONDS));
        trace.add("thread1 get success");
        fail("failure expected");
      } catch (CanceledFailure e) {
        trace.add("thread1 get cancellation");
      } catch (TimeoutException e) {
        trace.add("thread1 get timeout");
        // Test default value
      } catch (Exception e) {
        assertEquals(IllegalArgumentException.class, e.getCause().getClass());
        trace.add("thread1 get failure");
      }
      return trace;
    }
  }

  @Test
  public void testGetTimeout() {
    TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance();
    try {
      String testTaskQueue = "testTaskQueue";
      Worker worker = testEnv.newWorker(testTaskQueue);
      worker.registerWorkflowImplementationTypes(TestGetTimeout.class);
      testEnv.start();
      PromiseTestWorkflow workflow =
          testEnv
              .getWorkflowClient()
              .newWorkflowStub(
                  PromiseTestWorkflow.class,
                  WorkflowOptions.newBuilder().setTaskQueue(testTaskQueue).build());
      List<String> result = workflow.test();
      List<String> expected = Arrays.asList("thread1 begin", "thread1 get timeout");
      assertEquals(expected, result);
    } finally {
      testEnv.close();
    }
  }

  @Test
  public void testMultiple() {
    DeterministicRunner r =
        DeterministicRunner.newRunner(
            threadPool,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              trace.add("root begin");
              CompletablePromise<Boolean> f1 = Workflow.newPromise();
              CompletablePromise<Boolean> f2 = Workflow.newPromise();
              CompletablePromise<Boolean> f3 = Workflow.newPromise();

              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread1 begin");
                        assertTrue(f1.get());
                        trace.add("thread1 f1");
                        f2.complete(true);
                        trace.add("thread1 done");
                      },
                      false)
                  .start();
              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread2 begin");
                        assertTrue(f2.get());
                        trace.add("thread2 f2");
                        f3.complete(true);
                        trace.add("thread2 done");
                      },
                      false)
                  .start();
              f1.complete(true);
              assertFalse(f1.complete(false));
              trace.add("root before f3");
              assertTrue(f3.get());
              trace.add("root done");
            });
    r.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    String[] expected =
        new String[] {
          "root begin",
          "root before f3",
          "thread1 begin",
          "thread1 f1",
          "thread1 done",
          "thread2 begin",
          "thread2 f2",
          "thread2 done",
          "root done"
        };

    trace.setExpected(expected);
  }

  @Test
  public void tstAsync() {
    DeterministicRunner runner =
        DeterministicRunner.newRunner(
            threadPool,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              trace.add("root begin");
              CompletablePromise<String> f1 = Workflow.newPromise();
              CompletablePromise<String> f2 = Workflow.newPromise();
              Promise<String> f3 =
                  f1.thenApply((r) -> r + ".thenApply")
                      .thenCompose((r) -> f2.handle((v, e) -> r + v));
              CompletablePromise<String> f4 = Workflow.newPromise();
              f4.completeFrom(f3);
              f4.thenApply(
                      (r) -> {
                        trace.add(r);
                        return null;
                      })
                  .exceptionally(
                      (e) -> {
                        trace.add("exceptionally");
                        throw new RuntimeException("unexpected");
                      });
              f2.complete(".f2Handle");
              f1.complete("value1");
              trace.add("root done");
            });
    runner.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    String[] expected = new String[] {"root begin", "value1.thenApply.f2Handle", "root done"};

    trace.setExpected(expected);
  }

  @Test
  public void testAsyncFailure() {
    DeterministicRunner runner =
        DeterministicRunner.newRunner(
            threadPool,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              trace.add("root begin");
              CompletablePromise<String> f1 = Workflow.newPromise();
              CompletablePromise<String> f2 = Workflow.newPromise();
              Promise<String> f3 =
                  f1.thenApply((r) -> r + ".thenApply")
                      .thenCompose((r) -> f2.handle((v, e) -> r + v));
              CompletablePromise<String> f4 = Workflow.newPromise();
              f4.completeFrom(f3);
              Promise<String> f5 =
                  f4.thenApply(
                          (r) -> {
                            trace.add(r);
                            return "ignored";
                          })
                      .exceptionally(
                          (e) -> {
                            trace.add("exceptionally");
                            assertTrue(e instanceof IllegalThreadStateException);
                            throw new IllegalFormatCodePointException(10);
                          });
              f2.complete(".f2Handle");
              f1.completeExceptionally(new IllegalThreadStateException("Simulated"));
              try {
                f5.get();
                fail("unreachable");
              } catch (IllegalFormatCodePointException e) {
                assertEquals(10, e.getCodePoint());
                trace.add("failure caught");
              }
              trace.add("root done");
            });
    runner.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    String[] expected = new String[] {"root begin", "exceptionally", "failure caught", "root done"};

    trace.setExpected(expected);
  }

  @Test
  public void testAllOf() {
    DeterministicRunner r =
        DeterministicRunner.newRunner(
            threadPool,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              trace.add("root begin");
              CompletablePromise<String> f1 = Workflow.newPromise();
              CompletablePromise<String> f2 = Workflow.newPromise();
              CompletablePromise<String> f3 = Workflow.newPromise();

              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread1 begin");
                        f1.complete("value1");
                        trace.add("thread1 done");
                      },
                      false)
                  .start();
              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread3 begin");
                        f3.complete("value3");
                        trace.add("thread3 done");
                      },
                      false)
                  .start();
              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread2 begin");
                        f2.complete("value2");
                        trace.add("thread2 done");
                      },
                      false)
                  .start();
              List<Promise<String>> promises = new ArrayList<>();
              promises.add(f1);
              promises.add(f2);
              promises.add(f3);
              trace.add("root before allOf");
              Promise.allOf(promises).get();
              assertTrue(f1.isCompleted() && f2.isCompleted() && f3.isCompleted());
              trace.add("root done");
            });
    r.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    String[] expected =
        new String[] {
          "root begin",
          "root before allOf",
          "thread1 begin",
          "thread1 done",
          "thread3 begin",
          "thread3 done",
          "thread2 begin",
          "thread2 done",
          "root done"
        };
    trace.setExpected(expected);
  }

  @Test
  public void testAllOfImmediatelyReady() {
    DeterministicRunner r =
        DeterministicRunner.newRunner(
            threadPool,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              trace.add("root begin");
              {
                Promise<Void> all =
                    Promise.allOf(Workflow.newPromise("foo"), Workflow.newPromise("bar"));
                trace.add("root array isCompleted=" + all.get());
              }
              {
                Promise all = Promise.allOf();
                trace.add("root empty array isCompleted=" + all.get());
              }
              {
                Promise all = Promise.allOf(Collections.emptyList());
                trace.add("root empty list isCompleted=" + all.get());
              }
              {
                List<Promise<String>> list = new ArrayList<>();
                list.add(Workflow.newPromise("foo"));
                list.add(Workflow.newPromise("bar"));
                Promise<Void> all = Promise.allOf(list);
                trace.add("root collection isCompleted=" + all.get());
              }
              trace.add("root done");
            });
    r.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    String[] expected =
        new String[] {
          "root begin",
          "root array isCompleted=null",
          "root empty array isCompleted=null",
          "root empty list isCompleted=null",
          "root collection isCompleted=null",
          "root done"
        };
    trace.setExpected(expected);
  }

  @Test
  public void testAnyOf() {
    DeterministicRunner r =
        DeterministicRunner.newRunner(
            threadPool,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              trace.add("root begin");
              CompletablePromise<String> f1 = Workflow.newPromise();
              CompletablePromise<String> f2 = Workflow.newPromise();
              CompletablePromise<String> f3 = Workflow.newPromise();

              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread1 begin");
                        f1.complete("value1");
                        trace.add("thread1 done");
                      },
                      false)
                  .start();
              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread3 begin");
                        f3.complete("value3");
                        trace.add("thread3 done");
                      },
                      false)
                  .start();
              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread2 begin");
                        f2.complete("value2");
                        trace.add("thread2 done");
                      },
                      false)
                  .start();
              List<Promise<String>> promises = new ArrayList<>();
              promises.add(f1);
              promises.add(f2);
              promises.add(f3);
              trace.add("root before anyOf");
              Promise<String> any = Promise.anyOf(promises);
              // Relying on ordered execution of threads.
              assertEquals("value1", any.get());
              trace.add("root done");
            });
    r.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    String[] expected =
        new String[] {
          "root begin",
          "root before anyOf",
          "thread1 begin",
          "thread1 done",
          "thread3 begin",
          "thread3 done",
          "thread2 begin",
          "thread2 done",
          "root done"
        };
    trace.setExpected(expected);
  }

  @Test
  public void testAllOfArray() {
    DeterministicRunner r =
        DeterministicRunner.newRunner(
            threadPool,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              trace.add("root begin");
              CompletablePromise<String> f1 = Workflow.newPromise();
              CompletablePromise<Integer> f2 = Workflow.newPromise();
              CompletablePromise<Boolean> f3 = Workflow.newPromise();

              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread1 begin");
                        f1.complete("value1");
                        trace.add("thread1 done");
                      },
                      false)
                  .start();
              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread3 begin");
                        f3.complete(true);
                        trace.add("thread3 done");
                      },
                      false)
                  .start();
              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread2 begin");
                        f2.complete(111);
                        trace.add("thread2 done");
                      },
                      false)
                  .start();
              trace.add("root before allOf");
              assertFalse(f1.isCompleted());
              assertFalse(f2.isCompleted());
              assertFalse(f3.isCompleted());
              // Relying on ordered execution of threads.
              Promise<Object> done = Promise.anyOf(f3, f2, f1);
              assertEquals("value1", done.get());
              assertTrue(f1.isCompleted());
              trace.add("root done");
            });
    r.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    String[] expected =
        new String[] {
          "root begin",
          "root before allOf",
          "thread1 begin",
          "thread1 done",
          "thread3 begin",
          "thread3 done",
          "thread2 begin",
          "thread2 done",
          "root done"
        };
    trace.setExpected(expected);
  }

  @Test
  public void testAnyOfArray() {
    DeterministicRunner r =
        DeterministicRunner.newRunner(
            threadPool,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              trace.add("root begin");
              CompletablePromise<String> f1 = Workflow.newPromise();
              CompletablePromise<Integer> f2 = Workflow.newPromise();
              CompletablePromise<Boolean> f3 = Workflow.newPromise();

              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread1 begin");
                        f1.complete("value1");
                        trace.add("thread1 done");
                      },
                      false)
                  .start();
              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread3 begin");
                        f3.complete(true);
                        trace.add("thread3 done");
                      },
                      false)
                  .start();
              WorkflowThread.newThread(
                      () -> {
                        trace.add("thread2 begin");
                        f2.complete(111);
                        trace.add("thread2 done");
                      },
                      false)
                  .start();
              trace.add("root before allOf");
              assertFalse(f1.isCompleted());
              assertFalse(f2.isCompleted());
              assertFalse(f3.isCompleted());
              Promise<Void> done = Promise.allOf(f1, f2, f3);
              done.get();
              assertTrue(f1.isCompleted());
              assertTrue(f2.isCompleted());
              assertTrue(f3.isCompleted());
              trace.add("root done");
            });
    r.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    String[] expected =
        new String[] {
          "root begin",
          "root before allOf",
          "thread1 begin",
          "thread1 done",
          "thread3 begin",
          "thread3 done",
          "thread2 begin",
          "thread2 done",
          "root done"
        };
    trace.setExpected(expected);
  }

  @Test
  public void testAnyOfImmediatelyReady() {
    DeterministicRunner r =
        DeterministicRunner.newRunner(
            threadPool,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              trace.add("root begin");
              {
                Promise all = Promise.anyOf(Workflow.newPromise(), Workflow.newPromise("bar"));
                trace.add("root array isCompleted=" + all.isCompleted());
              }
              {
                Promise all = Promise.anyOf();
                trace.add("root empty array isCompleted=" + all.isCompleted());
              }
              {
                Promise all = Promise.anyOf(Collections.emptyList());
                trace.add("root empty list isCompleted=" + all.isCompleted());
              }
              {
                List<Promise<String>> list = new ArrayList<>();
                list.add(Workflow.newPromise());
                list.add(Workflow.newPromise("bar"));
                Promise all = Promise.anyOf(list);
                trace.add("root collection isCompleted=" + all.isCompleted());
              }
              trace.add("root done");
            });
    r.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    String[] expected =
        new String[] {
          "root begin",
          "root array isCompleted=true",
          "root empty array isCompleted=false",
          "root empty list isCompleted=false",
          "root collection isCompleted=true",
          "root done"
        };
    trace.setExpected(expected);
  }
}
