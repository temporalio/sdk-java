package io.temporal.internal.sync;

import static org.junit.Assert.*;

import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

/**
 * Unit tests for RepeatableWorkflowThread functionality. These tests verify the behavior of
 * workflow threads that repeatedly evaluate a condition on each runUntilBlocked() call.
 *
 * <p>RepeatableWorkflowThread is used for implementing await-style operations that need to
 * repeatedly check a condition across multiple workflow task executions.
 */
public class RepeatableWorkflowThreadTest {

  @Rule public final Tracer trace = new Tracer();

  private static ExecutorService threadPool;

  private String status;
  private boolean unblock;
  private Throwable failure;

  @BeforeClass
  public static void beforeClass() {
    threadPool = new ThreadPoolExecutor(1, 1000, 1, TimeUnit.SECONDS, new SynchronousQueue<>());
  }

  @AfterClass
  public static void afterClass() {
    threadPool.shutdown();
  }

  @Before
  public void setUp() {
    unblock = false;
    failure = null;
    status = "initial";
  }

  // ==================== Basic Condition Evaluation Tests ====================

  /** Test that when condition returns true immediately, the thread completes. */
  @Test(timeout = 5000)
  public void testConditionReturnsTrueImmediately_ThreadCompletes() {
    AtomicInteger evaluationCount = new AtomicInteger(0);

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              status = "started";

              // Create repeatable thread with condition that returns true immediately
              WorkflowThread repeatableThread =
                  DeterministicRunnerImpl.currentThreadInternal()
                      .getRunner()
                      .newRepeatableThread(
                          () -> {
                            evaluationCount.incrementAndGet();
                            return true; // Immediately true
                          },
                          false,
                          "test-repeatable");

              repeatableThread.start();

              // Wait for the repeatable thread to complete
              WorkflowThread.await(
                  "wait for repeatable", () -> repeatableThread.isDone() || unblock);

              status = "done";
            });

    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals("done", status);
    assertEquals(
        "Condition should be evaluated exactly once when it returns true immediately",
        1,
        evaluationCount.get());
    assertTrue(d.isDone());
  }

  /**
   * Test that condition returning false several times, then true, completes after multiple runs.
   */
  @Test(timeout = 5000)
  public void testConditionReturnsFalseThenTrue_ThreadCompletesAfterMultipleRuns() {
    AtomicInteger evaluationCount = new AtomicInteger(0);
    AtomicInteger targetCount = new AtomicInteger(3);

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              status = "started";

              WorkflowThread repeatableThread =
                  DeterministicRunnerImpl.currentThreadInternal()
                      .getRunner()
                      .newRepeatableThread(
                          () -> {
                            int count = evaluationCount.incrementAndGet();
                            // Return true on the third evaluation
                            return count >= targetCount.get();
                          },
                          false,
                          "test-repeatable");

              repeatableThread.start();

              // Wait for the repeatable thread to complete
              WorkflowThread.await("wait for repeatable", repeatableThread::isDone);

              status = "done";
            });

    // First runUntilAllBlocked: condition evaluated once, returns false
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertFalse(d.isDone());
    assertEquals(1, evaluationCount.get());

    // Second runUntilAllBlocked: condition evaluated again, returns false
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertFalse(d.isDone());
    assertEquals(2, evaluationCount.get());

    // Third runUntilAllBlocked: condition evaluated, returns true, thread completes
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals("done", status);
    assertEquals(3, evaluationCount.get());
    assertTrue(d.isDone());
  }

  /** Test that condition always returning false keeps thread alive and yields properly. */
  @Test(timeout = 5000)
  public void testConditionAlwaysFalse_ThreadStaysAliveAndYields() {
    AtomicInteger evaluationCount = new AtomicInteger(0);
    AtomicReference<WorkflowThread> threadRef = new AtomicReference<>();

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              status = "started";

              WorkflowThread repeatableThread =
                  DeterministicRunnerImpl.currentThreadInternal()
                      .getRunner()
                      .newRepeatableThread(
                          () -> {
                            evaluationCount.incrementAndGet();
                            return false; // Never satisfied
                          },
                          false,
                          "test-repeatable");

              threadRef.set(repeatableThread);
              repeatableThread.start();

              // Wait indefinitely (will be controlled externally)
              WorkflowThread.await("wait", () -> unblock);

              status = "done";
            });

    // Run multiple times
    for (int i = 1; i <= 5; i++) {
      d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
      assertFalse("Thread should not be done when condition is always false", d.isDone());
      assertEquals(
          "Condition should be evaluated once per runUntilAllBlocked", i, evaluationCount.get());

      // Verify the repeatable thread is not done
      WorkflowThread repeatableThread = threadRef.get();
      assertNotNull(repeatableThread);
      assertFalse(repeatableThread.isDone());
    }

    // Clean up by unblocking and closing
    d.close();
  }

  // ==================== Cancellation Tests ====================

  /** Test cancellation while the condition is being evaluated. */
  @Test(timeout = 5000)
  public void testCancellationWhileRunning_ThreadStops() {
    AtomicInteger evaluationCount = new AtomicInteger(0);

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              status = "started";

              WorkflowThread repeatableThread =
                  DeterministicRunnerImpl.currentThreadInternal()
                      .getRunner()
                      .newRepeatableThread(
                          () -> {
                            evaluationCount.incrementAndGet();
                            return false; // Never returns true
                          },
                          false,
                          "test-repeatable");
              repeatableThread.start();

              try {
                WorkflowThread.await("wait for repeatable", repeatableThread::isDone);
                status = "repeatable done";
              } catch (CanceledFailure e) {
                status = "canceled";
              }

              status = "done: " + status;
            });

    // First run - thread evaluates condition
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertFalse(d.isDone());

    // Cancel the workflow
    d.cancel("cancel workflow");
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);

    assertTrue(d.isDone());
    assertTrue("At least one evaluation should have happened", evaluationCount.get() >= 1);
  }

  /** Test cancellation while thread is yielded waiting. */
  @Test(timeout = 5000)
  public void testCancellationWhileYielded_ThreadStops() {
    AtomicInteger evaluationCount = new AtomicInteger(0);
    AtomicBoolean shouldSatisfy = new AtomicBoolean(false);
    AtomicReference<CancellationScope> scopeRef = new AtomicReference<>();

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              status = "started";

              CancellationScope scope =
                  Workflow.newCancellationScope(
                      () -> {
                        WorkflowThread repeatableThread =
                            DeterministicRunnerImpl.currentThreadInternal()
                                .getRunner()
                                .newRepeatableThread(
                                    () -> {
                                      evaluationCount.incrementAndGet();
                                      return shouldSatisfy.get();
                                    },
                                    false,
                                    "test-repeatable");
                        repeatableThread.start();

                        try {
                          WorkflowThread.await("wait for repeatable", repeatableThread::isDone);
                          status = "thread completed";
                        } catch (CanceledFailure e) {
                          status = "canceled";
                        }
                      });

              scopeRef.set(scope);
              scope.run();
              status = "done: " + status;
            });

    // First run - thread evaluates condition, returns false, yields
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals(1, evaluationCount.get());
    assertFalse(d.isDone());

    // Second run - thread is yielded, evaluate again
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals(2, evaluationCount.get());
    assertFalse(d.isDone());

    // Now cancel the scope while the thread is yielded
    d.cancel("cancel during yield");
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);

    assertTrue(d.isDone());
    assertTrue(status.contains("canceled") || status.contains("done"));
  }

  // ==================== Destroy Thread (stopNow) Tests ====================

  /** Test that stopNow properly destroys the thread. */
  @Test(timeout = 5000)
  public void testStopNow_DestroysThread() {
    AtomicInteger evaluationCount = new AtomicInteger(0);
    AtomicReference<Throwable> capturedError = new AtomicReference<>();

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              status = "started";

              WorkflowThread repeatableThread =
                  DeterministicRunnerImpl.currentThreadInternal()
                      .getRunner()
                      .newRepeatableThread(
                          () -> {
                            evaluationCount.incrementAndGet();
                            return false;
                          },
                          false,
                          "test-repeatable");
              repeatableThread.start();

              try {
                WorkflowThread.await("wait", () -> unblock);
              } catch (DestroyWorkflowThreadError e) {
                capturedError.set(e);
                throw e;
              }
              status = "done";
            });

    // Run to get the thread started
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertFalse(d.isDone());
    assertTrue(evaluationCount.get() >= 1);

    // Close/destroy the runner
    d.close();

    assertTrue(d.isDone());
    assertNotNull("DestroyWorkflowThreadError should be thrown", capturedError.get());
    assertTrue(capturedError.get() instanceof DestroyWorkflowThreadError);
  }

  // ==================== Exception in Condition Tests ====================

  /** Test that exception thrown in condition is properly captured. */
  @Test(timeout = 5000)
  public void testExceptionInCondition_ProperlyCaptured() {
    RuntimeException testException = new RuntimeException("condition exception");

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              status = "started";

              WorkflowThread repeatableThread =
                  DeterministicRunnerImpl.currentThreadInternal()
                      .getRunner()
                      .newRepeatableThread(
                          () -> {
                            throw testException;
                          },
                          false,
                          "test-repeatable");
              repeatableThread.start();

              WorkflowThread.await("wait", () -> unblock);
              status = "done";
            });

    try {
      d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
      fail("Expected exception to be thrown");
    } catch (RuntimeException e) {
      assertTrue(
          "Exception should contain original message",
          e.getMessage().contains("condition exception")
              || (e.getCause() != null && e.getCause().getMessage().contains("condition exception"))
              || e.equals(testException));
    }
    assertTrue(d.isDone());
  }

  // ==================== Multiple Concurrent RepeatableThreads Tests ====================

  /** Test multiple concurrent RepeatableThreads with different conditions. */
  @Test(timeout = 5000)
  public void testMultipleConcurrentRepeatableThreads() {
    AtomicInteger thread1Count = new AtomicInteger(0);
    AtomicInteger thread2Count = new AtomicInteger(0);
    AtomicInteger thread3Count = new AtomicInteger(0);

    AtomicBoolean satisfy1 = new AtomicBoolean(false);
    AtomicBoolean satisfy2 = new AtomicBoolean(false);
    AtomicBoolean satisfy3 = new AtomicBoolean(false);

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              status = "started";
              DeterministicRunnerImpl runner =
                  DeterministicRunnerImpl.currentThreadInternal().getRunner();

              // Create three repeatable threads with different conditions
              WorkflowThread t1 =
                  runner.newRepeatableThread(
                      () -> {
                        thread1Count.incrementAndGet();
                        return satisfy1.get();
                      },
                      false,
                      "repeatable-1");

              WorkflowThread t2 =
                  runner.newRepeatableThread(
                      () -> {
                        thread2Count.incrementAndGet();
                        return satisfy2.get();
                      },
                      false,
                      "repeatable-2");

              WorkflowThread t3 =
                  runner.newRepeatableThread(
                      () -> {
                        thread3Count.incrementAndGet();
                        return satisfy3.get();
                      },
                      false,
                      "repeatable-3");

              t1.start();
              t2.start();
              t3.start();

              // Wait for all three to complete
              WorkflowThread.await("wait all", () -> t1.isDone() && t2.isDone() && t3.isDone());

              status = "done";
            });

    // First run - all threads evaluate their conditions
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals(1, thread1Count.get());
    assertEquals(1, thread2Count.get());
    assertEquals(1, thread3Count.get());
    assertFalse(d.isDone());

    // Second run - all threads evaluate again
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals(2, thread1Count.get());
    assertEquals(2, thread2Count.get());
    assertEquals(2, thread3Count.get());
    assertFalse(d.isDone());

    // Satisfy thread 1
    satisfy1.set(true);
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals(3, thread1Count.get()); // Evaluated and completed
    assertEquals(3, thread2Count.get());
    assertEquals(3, thread3Count.get());
    assertFalse(d.isDone());

    // Satisfy threads 2 and 3
    satisfy2.set(true);
    satisfy3.set(true);
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals("done", status);
    assertTrue(d.isDone());
  }

  // ==================== Detached Thread Tests ====================

  /** Test that detached repeatable thread is not affected by parent cancellation. */
  @Test(timeout = 5000)
  public void testDetachedRepeatableThread_NotAffectedByParentCancellation() {
    AtomicInteger evaluationCount = new AtomicInteger(0);
    AtomicBoolean shouldSatisfy = new AtomicBoolean(false);
    AtomicReference<WorkflowThread> threadRef = new AtomicReference<>();

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              status = "started";

              // Create detached repeatable thread
              WorkflowThread repeatableThread =
                  DeterministicRunnerImpl.currentThreadInternal()
                      .getRunner()
                      .newRepeatableThread(
                          () -> {
                            evaluationCount.incrementAndGet();
                            return shouldSatisfy.get();
                          },
                          true, // detached
                          "detached-repeatable");

              threadRef.set(repeatableThread);
              repeatableThread.start();

              // Wait for external unblock
              WorkflowThread.await("wait", () -> unblock);

              status = "done";
            });

    // First run
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals(1, evaluationCount.get());
    assertFalse(d.isDone());

    // Cancel the workflow - detached thread should continue
    d.cancel("cancel workflow");
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);

    // The detached thread should still be running
    WorkflowThread detachedThread = threadRef.get();
    assertNotNull(detachedThread);

    // Thread may have evaluated more times during cancellation processing
    assertTrue(evaluationCount.get() >= 1);

    // Eventually satisfy and unblock
    shouldSatisfy.set(true);
    unblock = true;
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertTrue(d.isDone());
  }

  // ==================== Integration with Regular Workflow Threads Tests ====================

  /** Test RepeatableWorkflowThread working alongside regular WorkflowThreadImpl. */
  @Test(timeout = 5000)
  public void testRepeatableThreadWithRegularThread() {
    trace.add("init");
    AtomicInteger repeatableCount = new AtomicInteger(0);
    AtomicBoolean satisfyRepeatable = new AtomicBoolean(false);

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              trace.add("root started");
              DeterministicRunnerImpl runner =
                  DeterministicRunnerImpl.currentThreadInternal().getRunner();

              // Create regular thread
              Promise<Void> regularThread =
                  Async.procedure(
                      () -> {
                        trace.add("regular started");
                        WorkflowThread.await("wait regular", () -> unblock);
                        trace.add("regular done");
                      });

              // Create repeatable thread
              WorkflowThread repeatableThread =
                  runner.newRepeatableThread(
                      () -> {
                        repeatableCount.incrementAndGet();
                        return satisfyRepeatable.get();
                      },
                      false,
                      "test-repeatable");
              repeatableThread.start();

              // Wait for both
              trace.add("waiting for both");
              WorkflowThread.await(
                  "wait both", () -> regularThread.isCompleted() && repeatableThread.isDone());

              trace.add("root done");
            });

    // First run - both threads start
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertFalse(d.isDone());

    // Satisfy the repeatable thread
    satisfyRepeatable.set(true);
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertFalse(d.isDone()); // Regular thread still waiting

    // Unblock the regular thread
    unblock = true;
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertTrue(d.isDone());

    trace.setExpected(
        "init", "root started", "waiting for both", "regular started", "regular done", "root done");
  }

  // ==================== Edge Case Tests ====================

  /** Test repeatable thread with condition that alternates between true and false. */
  @Test(timeout = 5000)
  public void testConditionAlternates_CompletesOnFirstTrue() {
    AtomicInteger evaluationCount = new AtomicInteger(0);

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              status = "started";

              WorkflowThread repeatableThread =
                  DeterministicRunnerImpl.currentThreadInternal()
                      .getRunner()
                      .newRepeatableThread(
                          () -> {
                            int count = evaluationCount.incrementAndGet();
                            // Return true only on even counts
                            return count % 2 == 0;
                          },
                          false,
                          "test-repeatable");

              repeatableThread.start();
              WorkflowThread.await("wait", repeatableThread::isDone);
              status = "done";
            });

    // First run - count=1, returns false
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals(1, evaluationCount.get());
    assertFalse(d.isDone());

    // Second run - count=2, returns true
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals(2, evaluationCount.get());
    assertEquals("done", status);
    assertTrue(d.isDone());
  }

  /** Test thread name is correctly set. */
  @Test(timeout = 5000)
  public void testThreadNameIsSet() {
    AtomicReference<String> capturedName = new AtomicReference<>();

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              WorkflowThread repeatableThread =
                  DeterministicRunnerImpl.currentThreadInternal()
                      .getRunner()
                      .newRepeatableThread(() -> true, false, "my-custom-repeatable-name");

              capturedName.set(repeatableThread.getName());
              repeatableThread.start();

              WorkflowThread.await("wait", repeatableThread::isDone);
            });

    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals("my-custom-repeatable-name", capturedName.get());
    assertTrue(d.isDone());
  }

  /** Test that thread priority is correctly assigned. */
  @Test(timeout = 5000)
  public void testThreadPriorityIsAssigned() {
    AtomicInteger capturedPriority = new AtomicInteger(-1);

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              WorkflowThread repeatableThread =
                  DeterministicRunnerImpl.currentThreadInternal()
                      .getRunner()
                      .newRepeatableThread(() -> true, false, "test-repeatable");

              capturedPriority.set(repeatableThread.getPriority());
              repeatableThread.start();

              WorkflowThread.await("wait", repeatableThread::isDone);
            });

    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertTrue("Priority should be positive", capturedPriority.get() > 0);
    assertTrue(d.isDone());
  }

  /** Test getting stack trace from repeatable thread. */
  @Test(timeout = 5000)
  public void testStackTraceCanBeObtained() {
    AtomicReference<String> stackTrace = new AtomicReference<>();

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              WorkflowThread repeatableThread =
                  DeterministicRunnerImpl.currentThreadInternal()
                      .getRunner()
                      .newRepeatableThread(
                          () -> false, // Never completes
                          false,
                          "test-repeatable");

              repeatableThread.start();
              WorkflowThread.await("wait", () -> unblock);
            });

    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);

    stackTrace.set(d.stackTrace());
    assertNotNull(stackTrace.get());
    assertTrue(
        "Stack trace should contain the repeatable thread name",
        stackTrace.get().contains("test-repeatable") || stackTrace.get().contains("workflow-root"));

    d.close();
  }

  // ==================== Condition Side Effects Tests ====================

  /** Test that condition can modify external state safely. */
  @Test(timeout = 5000)
  public void testConditionCanModifyExternalState() {
    AtomicInteger counter = new AtomicInteger(0);
    AtomicInteger targetValue = new AtomicInteger(5);

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              status = "started";

              WorkflowThread repeatableThread =
                  DeterministicRunnerImpl.currentThreadInternal()
                      .getRunner()
                      .newRepeatableThread(
                          () -> {
                            // Condition increments counter as a side effect
                            int current = counter.incrementAndGet();
                            return current >= targetValue.get();
                          },
                          false,
                          "test-repeatable");

              repeatableThread.start();
              WorkflowThread.await("wait", repeatableThread::isDone);
              status = "counter=" + counter.get();
            });

    // Run until completion (use do-while to ensure at least one run)
    do {
      d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    } while (!d.isDone());

    assertEquals("counter=5", status);
    assertEquals(5, counter.get());
  }

  /** Test condition that depends on workflow time. */
  @Test(timeout = 5000)
  public void testConditionDependsOnWorkflowState() {
    AtomicInteger evaluationCount = new AtomicInteger(0);
    AtomicBoolean externalSignal = new AtomicBoolean(false);

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              status = "started";

              WorkflowThread repeatableThread =
                  DeterministicRunnerImpl.currentThreadInternal()
                      .getRunner()
                      .newRepeatableThread(
                          () -> {
                            evaluationCount.incrementAndGet();
                            // Condition depends on external signal
                            return externalSignal.get();
                          },
                          false,
                          "test-repeatable");

              repeatableThread.start();
              WorkflowThread.await("wait", repeatableThread::isDone);
              status = "done after " + evaluationCount.get() + " evaluations";
            });

    // Run a few times without signal
    for (int i = 0; i < 3; i++) {
      d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
      assertFalse(d.isDone());
    }

    // Set external signal
    externalSignal.set(true);
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);

    assertTrue(status.startsWith("done after"));
    assertTrue(d.isDone());
  }

  // ==================== Blocking Condition Tests ====================

  /**
   * Test that condition code can use Workflow.await() to block. This verifies that the
   * RepeatableWorkflowThread properly supports full workflow capabilities inside conditions.
   */
  @Test(timeout = 5000)
  public void testConditionWithBlockingAwait() {
    AtomicInteger conditionEvaluationCount = new AtomicInteger(0);
    AtomicBoolean awaitSignal = new AtomicBoolean(false);
    AtomicBoolean conditionResult = new AtomicBoolean(false);

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              status = "started";

              // Create repeatable thread with a condition that blocks using Workflow.await()
              WorkflowThread repeatableThread =
                  DeterministicRunnerImpl.currentThreadInternal()
                      .getRunner()
                      .newRepeatableThread(
                          () -> {
                            conditionEvaluationCount.incrementAndGet();

                            // Block until awaitSignal becomes true
                            // This tests that await works inside condition code
                            Workflow.await(awaitSignal::get);

                            // After the await unblocks, return the condition result
                            return conditionResult.get();
                          },
                          false,
                          "blocking-condition");

              repeatableThread.start();
              WorkflowThread.await("wait for repeatable", repeatableThread::isDone);
              status = "done";
            });

    // First run - condition starts evaluating but blocks on Workflow.await()
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals(1, conditionEvaluationCount.get());
    assertFalse(d.isDone());

    // Second run - still blocked on await (no signal yet)
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals(1, conditionEvaluationCount.get()); // Same evaluation, still blocked
    assertFalse(d.isDone());

    // Unblock the await inside the condition, but condition returns false
    // The first evaluation completes
    awaitSignal.set(true);
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals(1, conditionEvaluationCount.get()); // Same evaluation completed
    assertFalse(d.isDone());

    // Next run creates a new evaluation (since previous completed with false)
    // Reset signal so it blocks again
    awaitSignal.set(false);
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals(2, conditionEvaluationCount.get()); // New evaluation started
    assertFalse(d.isDone());

    // Now set signal and make condition return true
    awaitSignal.set(true);
    conditionResult.set(true);
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);

    assertEquals("done", status);
    assertTrue(d.isDone());
  }

  /**
   * Test that condition can block multiple times in sequence. This verifies that the internal
   * workflow thread properly handles multiple yield points within a single condition evaluation.
   */
  @Test(timeout = 5000)
  public void testConditionWithMultipleBlockingAwaits() {
    AtomicInteger step = new AtomicInteger(0);
    AtomicBoolean signal1 = new AtomicBoolean(false);
    AtomicBoolean signal2 = new AtomicBoolean(false);
    AtomicBoolean signal3 = new AtomicBoolean(false);

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              status = "started";

              WorkflowThread repeatableThread =
                  DeterministicRunnerImpl.currentThreadInternal()
                      .getRunner()
                      .newRepeatableThread(
                          () -> {
                            // First await
                            step.set(1);
                            Workflow.await(signal1::get);

                            // Second await
                            step.set(2);
                            Workflow.await(signal2::get);

                            // Third await
                            step.set(3);
                            Workflow.await(signal3::get);

                            step.set(4);
                            return true; // Complete after all awaits
                          },
                          false,
                          "multi-await-condition");

              repeatableThread.start();
              WorkflowThread.await("wait for repeatable", repeatableThread::isDone);
              status = "done at step " + step.get();
            });

    // First run - blocks on first await
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals(1, step.get());
    assertFalse(d.isDone());

    // Unblock first await
    signal1.set(true);
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals(2, step.get()); // Now at second await
    assertFalse(d.isDone());

    // Unblock second await
    signal2.set(true);
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals(3, step.get()); // Now at third await
    assertFalse(d.isDone());

    // Unblock third await - condition should complete with true
    signal3.set(true);
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals("done at step 4", status);
    assertTrue(d.isDone());
  }
}
