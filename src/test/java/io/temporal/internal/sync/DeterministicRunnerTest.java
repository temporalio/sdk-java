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

import static junit.framework.TestCase.*;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.*;

import com.google.common.collect.Maps;
import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.StatsReporter;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder;
import io.temporal.common.RetryOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.metrics.MetricsTag;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.replay.ReplayWorkflowContext;
import io.temporal.internal.replay.WorkflowExecutor;
import io.temporal.internal.replay.WorkflowExecutorCache;
import io.temporal.testUtils.HistoryUtils;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class DeterministicRunnerTest {

  @Rule public final Tracer trace = new Tracer();

  private String status;
  private boolean unblock1;
  private boolean unblock2;
  private Throwable failure;
  private long currentTime;
  private ExecutorService threadPool;

  @Before
  public void setUp() {
    unblock1 = false;
    unblock2 = false;
    failure = null;
    status = "initial";
    currentTime = 0;
    threadPool = new ThreadPoolExecutor(1, 1000, 1, TimeUnit.SECONDS, new SynchronousQueue<>());
  }

  @After
  public void tearDown() throws InterruptedException {
    threadPool.shutdown();
    threadPool.awaitTermination(10, TimeUnit.SECONDS);
  }

  @Test
  public void testYield() throws Throwable {
    DeterministicRunner d =
        new DeterministicRunnerImpl(
            () -> {
              status = "started";
              WorkflowThread.await("reason1", () -> unblock1);
              status = "after1";
              WorkflowThread.await("reason2", () -> unblock2);
              status = "done";
            });
    assertEquals("initial", status);
    d.runUntilAllBlocked();
    assertEquals("started", status);
    assertFalse(d.isDone());
    unblock1 = true;
    d.runUntilAllBlocked();
    assertEquals("after1", status);
    // Just check that running again doesn't make any progress.
    d.runUntilAllBlocked();
    assertEquals("after1", status);
    unblock2 = true;
    d.runUntilAllBlocked();
    assertEquals("done", status);
    assertTrue(d.isDone());
  }

  /**
   * Async retry cannot be tested here as it relies on timer that is implemented outside of
   * Dispatcher.
   *
   * @see WorkflowTest#testAsyncRetry()
   */
  @Test
  @Ignore // timer removed from dispatcher
  public void testRetry() throws Throwable {
    RetryOptions retryOptions =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(10))
            .setMaximumInterval(Duration.ofSeconds(100))
            .setBackoffCoefficient(2.0)
            .build();
    Duration expiration = Duration.ofMinutes(5);
    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool,
            null,
            () -> {
              trace.add("started");
              Workflow.retry(
                  retryOptions,
                  Optional.of(expiration),
                  () -> {
                    trace.add("retry at " + Workflow.currentTimeMillis());
                    throw new IllegalThreadStateException("simulated");
                  });
              trace.add("beforeSleep");
              Workflow.sleep(60000);
              trace.add("done");
            });
    try {
      for (int i = 0; i < Duration.ofSeconds(400).toMillis(); i += 10) {
        d.runUntilAllBlocked();
      }
      fail("failure expected");
    } catch (IllegalThreadStateException e) {
      assertEquals("simulated", e.getMessage());
    }
    int attempt = 1;
    long time = 0;
    trace.addExpected("started");
    while (time < expiration.toMillis()) {
      trace.addExpected("retry at " + time);
      long sleepMillis =
          (long)
              (Math.pow(retryOptions.getBackoffCoefficient(), attempt - 1)
                  * retryOptions.getInitialInterval().toMillis());
      sleepMillis = Math.min(sleepMillis, retryOptions.getMaximumInterval().toMillis());
      attempt++;
      time += sleepMillis;
    }
  }

  @Test
  public void testRootFailure() throws Throwable {
    DeterministicRunner d =
        new DeterministicRunnerImpl(
            () -> {
              status = "started";
              WorkflowThread.await("reason1", () -> unblock1);
              throw new RuntimeException("simulated");
            });
    assertEquals("initial", status);
    d.runUntilAllBlocked();
    assertEquals("started", status);
    assertFalse(d.isDone());
    unblock1 = true;
    try {
      d.runUntilAllBlocked();
      fail("exception expected");
    } catch (Exception ignored) {
    }
    assertTrue(d.isDone());
  }

  @Test
  public void testDispatcherStop() throws Throwable {
    DeterministicRunner d =
        new DeterministicRunnerImpl(
            () -> {
              status = "started";
              WorkflowThread.await("reason1", () -> unblock1);
              status = "after1";
              try {
                WorkflowThread.await("reason2", () -> unblock2);
              } catch (DestroyWorkflowThreadError e) {
                failure = e;
                throw e;
              }
              status = "done";
            });
    assertEquals("initial", status);
    d.runUntilAllBlocked();
    assertEquals("started", status);
    assertFalse(d.isDone());
    unblock1 = true;
    d.runUntilAllBlocked();
    assertEquals("after1", status);
    d.close();
    assertTrue(d.isDone());
    assertNotNull(failure);
  }

  @Test
  public void testDispatcherExit() throws Throwable {
    DeterministicRunner d =
        new DeterministicRunnerImpl(
            () -> {
              trace.add("root started");
              Promise<Void> thread1 =
                  Async.procedure(
                      () -> {
                        trace.add("child1 started");
                        WorkflowThread.await("reason1", () -> unblock1);
                        trace.add("child1 done");
                      });
              Promise<Void> thread2 =
                  Async.procedure(
                      () -> {
                        trace.add("child2 started");
                        WorkflowThread.await("reason2", () -> unblock2);
                        trace.add("child2 exiting");
                        WorkflowThread.exit("exitValue");
                        trace.add("child2 done");
                      });
              thread1.get();
              thread2.get();
              trace.add("root done");
            });
    d.runUntilAllBlocked();
    assertFalse(d.isDone());
    unblock2 = true;
    d.runUntilAllBlocked();
    assertTrue(d.isDone());
    assertEquals("exitValue", d.getExitValue());
    String[] expected =
        new String[] {
          "root started", "child1 started", "child2 started", "child2 exiting",
        };
    trace.setExpected(expected);
  }

  @Test
  public void testRootCancellation() throws Throwable {
    trace.add("init");
    DeterministicRunner d =
        new DeterministicRunnerImpl(
            () -> {
              trace.add("root started");
              WorkflowThread.await(
                  "reason1", () -> CancellationScope.current().isCancelRequested());
              trace.add("second await: " + CancellationScope.current().getCancellationReason());
              WorkflowThread.await(
                  "reason1", () -> CancellationScope.current().isCancelRequested());
              trace.add("root done");
            });
    d.runUntilAllBlocked();
    assertFalse(d.isDone());
    d.cancel("I just feel like it");
    d.runUntilAllBlocked();
    assertTrue(d.isDone());
    String[] expected =
        new String[] {
          "init", "root started", "second await: I just feel like it", "root done",
        };
    trace.setExpected(expected);
  }

  @Test
  public void testExplicitScopeCancellation() throws Throwable {
    trace.add("init");
    DeterministicRunner d =
        new DeterministicRunnerImpl(
            () -> {
              trace.add("root started");
              CompletablePromise<Void> var = Workflow.newPromise();
              CancellationScope scope =
                  Workflow.newCancellationScope(
                      () -> {
                        trace.add("scope started");
                        var.completeFrom(newTimer(300));
                        trace.add("scope done");
                      });
              scope.run();
              trace.add("root before cancel");
              scope.cancel("from root");
              try {
                var.get();
                trace.add("after get");
              } catch (CanceledFailure e) {
                trace.add("scope cancelled");
              }
              trace.add("root done");
            });
    d.runUntilAllBlocked();
    assertTrue(trace.toString(), d.isDone());
    String[] expected =
        new String[] {
          "init",
          "root started",
          "scope started",
          "scope done",
          "root before cancel",
          "timer cancelled",
          "scope cancelled",
          "root done",
        };
    trace.setExpected(expected);
  }

  @Test
  public void testExplicitDetachedScopeCancellation() throws Throwable {
    trace.add("init");
    DeterministicRunner d =
        new DeterministicRunnerImpl(
            () -> {
              trace.add("root started");
              CompletablePromise<Void> var = Workflow.newPromise();
              CancellationScope scope =
                  Workflow.newDetachedCancellationScope(
                      () -> {
                        trace.add("scope started");
                        var.completeFrom(newTimer(300));
                        trace.add("scope done");
                      });
              scope.run();
              trace.add("root before cancel");
              scope.cancel("from root");
              try {
                var.get();
                trace.add("after get");
              } catch (CanceledFailure e) {
                trace.add("scope cancelled");
              }
              trace.add("root done");
            });
    d.runUntilAllBlocked();
    assertTrue(trace.toString(), d.isDone());
    String[] expected =
        new String[] {
          "init",
          "root started",
          "scope started",
          "scope done",
          "root before cancel",
          "timer cancelled",
          "scope cancelled",
          "root done",
        };
    trace.setExpected(expected);
  }

  private Promise<Void> newTimer(int milliseconds) {
    return Async.procedure(
        () -> {
          try {
            Workflow.sleep(milliseconds);
            trace.add("timer fired");
          } catch (CanceledFailure e) {
            trace.add("timer cancelled");
            throw e;
          }
        });
  }

  @Test
  public void testExplicitThreadCancellation() throws Throwable {
    trace.add("init");
    DeterministicRunner d =
        new DeterministicRunnerImpl(
            () -> {
              trace.add("root started");
              CompletablePromise<String> threadDone = Workflow.newPromise();
              CancellationScope scope =
                  Workflow.newCancellationScope(
                      () -> {
                        Async.procedure(
                            () -> {
                              trace.add("thread started");
                              Promise<String> cancellation =
                                  CancellationScope.current().getCancellationRequest();
                              WorkflowThread.await(
                                  "reason1", () -> CancellationScope.current().isCancelRequested());
                              threadDone.completeFrom(cancellation);
                              trace.add("thread done: " + cancellation.get());
                            });
                      });
              scope.run();
              trace.add("root before cancel");
              scope.cancel("from root");
              threadDone.get();
              trace.add("root done");
            });

    d.runUntilAllBlocked();
    assertTrue(d.stackTrace(), d.isDone());
    String[] expected =
        new String[] {
          "init",
          "root started",
          "root before cancel",
          "thread started",
          "thread done: from root",
          "root done",
        };
    trace.setExpected(expected);
  }

  @Test
  public void testExplicitCancellationOnFailure() throws Throwable {
    trace.add("init");
    DeterministicRunner d =
        new DeterministicRunnerImpl(
            () -> {
              trace.add("root started");
              Workflow.newCancellationScope(
                      (scope) -> {
                        Promise<Void> p1 =
                            Async.procedure(
                                () -> {
                                  trace.add("thread1 started");
                                  try {
                                    Workflow.sleep(Duration.ofHours(1));
                                  } catch (Exception e) {
                                    trace.add("thread1 exception: " + e.getClass().getSimpleName());
                                  } finally {
                                    trace.add("thread1 done");
                                  }
                                });
                        Promise<Void> p2 =
                            Async.procedure(
                                    () -> {
                                      trace.add("thread2 started");
                                      try {
                                        throw new RuntimeException("simulated");
                                      } finally {
                                        trace.add("thread2 done");
                                      }
                                    })
                                .exceptionally(
                                    ex -> {
                                      scope.cancel();
                                      return null;
                                    });
                        Promise.allOf(p1, p2).get();
                      })
                  .run();
              trace.add("root done");
            });

    d.runUntilAllBlocked();
    assertTrue(d.stackTrace(), d.isDone());
    String[] expected =
        new String[] {
          "init",
          "root started",
          "thread1 started",
          "thread2 started",
          "thread2 done",
          "thread1 exception: CanceledFailure",
          "thread1 done",
          "root done"
        };
    trace.setExpected(expected);
  }

  @Test
  public void testDetachedCancellation() throws Throwable {
    trace.add("init");
    DeterministicRunner d =
        new DeterministicRunnerImpl(
            () -> {
              trace.add("root started");
              CompletablePromise<Void> done = Workflow.newPromise();
              Workflow.newDetachedCancellationScope(
                      () -> {
                        Async.procedure(
                            () -> {
                              trace.add("thread started");
                              WorkflowThread.await(
                                  "reason1",
                                  () ->
                                      unblock1 || CancellationScope.current().isCancelRequested());
                              if (CancellationScope.current().isCancelRequested()) {
                                done.completeExceptionally(new CanceledFailure("test"));
                              } else {
                                done.complete(null);
                              }
                              trace.add("await done");
                            });
                      })
                  .run();
              try {
                done.get();
              } catch (CanceledFailure e) {
                trace.add("done cancelled");
              }
              trace.add("root done");
            });
    d.runUntilAllBlocked();
    assertFalse(trace.toString(), d.isDone());
    d.cancel("I just feel like it");
    d.runUntilAllBlocked();
    assertFalse(d.isDone());
    String[] expected =
        new String[] {
          "init", "root started", "thread started",
        };
    trace.setExpected(expected);
    trace.assertExpected();
    unblock1 = true;
    d.runUntilAllBlocked();
    assertTrue(d.stackTrace(), d.isDone());
    expected =
        new String[] {
          "init", "root started", "thread started", "await done", "root done",
        };
    trace.setExpected(expected);
  }

  @Test
  public void testChild() throws Throwable {
    DeterministicRunner d =
        new DeterministicRunnerImpl(
            () -> {
              Promise<Void> async =
                  Async.procedure(
                      () -> {
                        status = "started";
                        WorkflowThread.await("reason1", () -> unblock1);
                        status = "after1";
                        WorkflowThread.await("reason2", () -> unblock2);
                        status = "done";
                      });
              async.get();
            });
    assertEquals("initial", status);
    d.runUntilAllBlocked();
    assertEquals("started", status);
    assertFalse(d.isDone());
    unblock1 = true;
    d.runUntilAllBlocked();
    assertEquals("after1", status);
    // Just check that running again doesn't make any progress.
    d.runUntilAllBlocked();
    assertEquals("after1", status);
    unblock2 = true;
    d.runUntilAllBlocked();
    assertEquals("done", status);
    assertTrue(d.isDone());
  }

  private static final int CHILDREN = 10;

  private class TestChildTreeRunnable implements Functions.Proc {

    final int depth;

    private TestChildTreeRunnable(int depth) {
      this.depth = depth;
    }

    @Override
    public void apply() {
      trace.add("child " + depth + " started");
      if (depth >= CHILDREN) {
        trace.add("child " + depth + " done");
        return;
      }
      Promise<Void> thread = Async.procedure(new TestChildTreeRunnable(depth + 1));
      WorkflowThread.await("reason1", () -> unblock1);
      thread.get();
      trace.add("child " + depth + " done");
    }
  }

  @Test
  public void testChildTree() throws Throwable {
    DeterministicRunner d = new DeterministicRunnerImpl(new TestChildTreeRunnable(0)::apply);
    d.runUntilAllBlocked();
    unblock1 = true;
    d.runUntilAllBlocked();
    assertTrue(d.isDone());
    List<String> expected = new ArrayList<>();
    for (int i = 0; i <= CHILDREN; i++) {
      expected.add("child " + i + " started");
    }
    for (int i = CHILDREN; i >= 0; i--) {
      expected.add("child " + i + " done");
    }
    trace.setExpected(expected.toArray(new String[0]));
  }

  @Test
  public void workflowThreadsWillEvictCacheWhenMaxThreadCountIsHit() throws Throwable {
    // Arrange
    // Arrange
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.NAMESPACE, "namespace")
            .put(MetricsTag.TASK_QUEUE, "stickyTaskQueue")
            .build();
    StatsReporter reporter = mock(StatsReporter.class);
    Scope scope =
        new RootScopeBuilder()
            .reporter(reporter)
            .reportEvery(com.uber.m3.util.Duration.ofMillis(300))
            .tagged(tags);
    ThreadPoolExecutor threadPool =
        new ThreadPoolExecutor(1, 3, 1, TimeUnit.SECONDS, new SynchronousQueue<>());
    AtomicReference<String> status = new AtomicReference<>();

    WorkflowExecutorCache cache = new WorkflowExecutorCache(3, scope);
    ReplayWorkflowContext replayWorkflowContext = mock(ReplayWorkflowContext.class);
    when(replayWorkflowContext.getMetricsScope()).thenReturn(scope);
    when(replayWorkflowContext.getNamespace()).thenReturn("namespace");
    when(replayWorkflowContext.getWorkflowType()).thenReturn(WorkflowType.getDefaultInstance());

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool,
            new SyncWorkflowContext(
                replayWorkflowContext, DataConverter.getDefaultInstance(), null, null),
            () -> {
              Promise<Void> thread =
                  Async.procedure(
                      () -> {
                        status.set("started");
                        WorkflowThread.await("doing something", () -> false);
                        status.set("done");
                      });
              thread.get();
            },
            cache);
    WorkflowExecutor workflowExecutor = new DetermisiticRunnerContainerWorkflowExecutor(d);
    PollWorkflowTaskQueueResponse response = HistoryUtils.generateWorkflowTaskWithInitialHistory();

    cache.getOrCreate(response, new com.uber.m3.tally.NoopScope(), () -> workflowExecutor);
    cache.addToCache(response.getWorkflowExecution().getRunId(), workflowExecutor);
    d.runUntilAllBlocked();
    assertEquals(2, threadPool.getActiveCount());
    assertEquals(1, cache.size());

    DeterministicRunnerImpl d2 =
        new DeterministicRunnerImpl(
            threadPool,
            new SyncWorkflowContext(
                replayWorkflowContext, DataConverter.getDefaultInstance(), null, null),
            () -> {
              Promise<Void> thread =
                  Async.procedure(
                      () -> {
                        status.set("started");
                        WorkflowThread.await("doing something else", () -> false);
                        status.set("done");
                      });
              thread.get();
            },
            cache);
    // Act: This should kick out threads consumed by 'd'
    d2.runUntilAllBlocked();

    // Assert: Cache is evicted and only threads consumed by d2 remain.
    assertEquals(2, threadPool.getActiveCount());
    assertEquals(0, cache.size()); // cache was evicted
    // Wait for reporter
    Thread.sleep(600);
    verify(reporter, atLeastOnce())
        .reportCounter(eq(MetricsType.STICKY_CACHE_THREAD_FORCED_EVICTION), eq(tags), anyInt());
  }

  @Test
  public void workflowThreadsWillNotEvictCacheWhenMaxThreadCountIsHit() throws Throwable {
    // Arrange
    ThreadPoolExecutor threadPool =
        new ThreadPoolExecutor(1, 5, 1, TimeUnit.SECONDS, new SynchronousQueue<>());
    AtomicReference<String> status = new AtomicReference<>();

    WorkflowExecutorCache cache = new WorkflowExecutorCache(3, new NoopScope());

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool,
            null,
            () -> {
              Promise<Void> thread =
                  Async.procedure(
                      () -> {
                        status.set("started");
                        WorkflowThread.await("doing something", () -> false);
                        status.set("done");
                      });
              thread.get();
            },
            cache);
    WorkflowExecutor workflowExecutor = new DetermisiticRunnerContainerWorkflowExecutor(d);
    PollWorkflowTaskQueueResponse response = HistoryUtils.generateWorkflowTaskWithInitialHistory();

    cache.getOrCreate(response, new com.uber.m3.tally.NoopScope(), () -> workflowExecutor);
    cache.addToCache(response.getWorkflowExecution().getRunId(), workflowExecutor);
    d.runUntilAllBlocked();
    assertEquals(2, threadPool.getActiveCount());
    assertEquals(1, cache.size());

    DeterministicRunnerImpl d2 =
        new DeterministicRunnerImpl(
            threadPool,
            null,
            () -> {
              Promise<Void> thread =
                  Async.procedure(
                      () -> {
                        status.set("started");
                        WorkflowThread.await("doing something else", () -> false);
                        status.set("done");
                      });
              thread.get();
            },
            cache);

    // Act: This should not kick out threads consumed by 'd' since there's enough capacity
    d2.runUntilAllBlocked();

    // Assert: Cache is not evicted and all threads remain.
    assertEquals(4, threadPool.getActiveCount());
    assertEquals(1, cache.size());
  }

  private static class DetermisiticRunnerContainerWorkflowExecutor implements WorkflowExecutor {
    DeterministicRunner runner;

    DetermisiticRunnerContainerWorkflowExecutor(DeterministicRunner runner) {
      this.runner = Objects.requireNonNull(runner);
    }

    @Override
    public WorkflowTaskResult handleWorkflowTask(
        PollWorkflowTaskQueueResponseOrBuilder workflowTask) {
      return new WorkflowTaskResult(new ArrayList<>(), Maps.newHashMap(), false, false);
    }

    @Override
    public Optional<Payloads> handleQueryWorkflowTask(
        PollWorkflowTaskQueueResponseOrBuilder workflowTask, WorkflowQuery query) {
      return Optional.empty();
    }

    @Override
    public void close() {
      runner.close();
    }

    @Override
    public Duration getWorkflowTaskTimeout() {
      return Duration.ofSeconds(10);
    }
  }

  @Test
  public void testRejectedExecutionError() {
    ThreadPoolExecutor threadPool =
        new ThreadPoolExecutor(0, 1, 1, TimeUnit.SECONDS, new SynchronousQueue<>());

    DeterministicRunner d =
        new DeterministicRunnerImpl(
            threadPool,
            null,
            () -> {
              Promise<Void> async = Async.procedure(() -> status = "started");
              async.get();
            });

    assertEquals("initial", status);

    try {
      d.runUntilAllBlocked();
    } catch (Throwable t) {
      assertTrue(t instanceof WorkflowRejectedExecutionError);
    }
  }
}
