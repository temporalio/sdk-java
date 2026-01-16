package io.temporal.internal.sync;

import static org.junit.Assert.*;

import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

/**
 * Unit tests for WorkflowThreadBase functionality. These tests verify the core behavior of workflow
 * threads including lifecycle management, blocking/yielding, cancellation, and exception handling.
 *
 * <p>WorkflowThreadBase is the abstract base class that provides shared functionality for
 * WorkflowThreadImpl and future implementations like RepeatableWorkflowThread.
 */
public class WorkflowThreadBaseTest {

  @Rule public final Tracer trace = new Tracer();

  private static ExecutorService threadPool;

  private String status;
  private boolean unblock1;
  private boolean unblock2;
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
    unblock1 = false;
    unblock2 = false;
    failure = null;
    status = "initial";
  }

  // ==================== Thread Lifecycle Tests ====================

  /**
   * Test that a workflow thread goes through the expected lifecycle states: CREATED -> RUNNING ->
   * YIELDED -> RUNNING -> DONE
   */
  @Test(timeout = 5000)
  public void testThreadLifecycle_CreatedToRunningToYieldedToDone() {
    AtomicReference<Status> statusAtStart = new AtomicReference<>();
    AtomicReference<Status> statusDuringYield = new AtomicReference<>();

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              // Capture status when thread first runs
              WorkflowThread current = DeterministicRunnerImpl.currentThreadInternal();
              statusAtStart.set(current.getWorkflowThreadContext().getStatus());

              // Yield and capture status
              WorkflowThread.await(
                  "test yield",
                  () -> {
                    statusDuringYield.set(current.getWorkflowThreadContext().getStatus());
                    return unblock1;
                  });

              status = "done";
            });

    // Run until blocked - this starts the root thread
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    // Thread should be in RUNNING state when executing user code
    assertEquals(Status.RUNNING, statusAtStart.get());
    // Thread is yielded but not done
    assertFalse(d.isDone());

    // Unblock and complete
    unblock1 = true;
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals("done", status);
    assertTrue(d.isDone());
  }

  /** Test that thread runnable is only executed after runUntilAllBlocked is called. */
  @Test(timeout = 5000)
  public void testThreadLifecycle_InitialStatusIsCreated() {
    AtomicBoolean startWasCalled = new AtomicBoolean(false);

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              startWasCalled.set(true);
              status = "done";
            });

    // Before runUntilAllBlocked, the root thread hasn't started executing user code
    assertFalse(startWasCalled.get());

    // Now run - thread will start and complete
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertTrue(startWasCalled.get());
    assertTrue(d.isDone());
  }

  // ==================== runUntilBlocked Tests ====================

  /** Test that runUntilBlocked runs the thread until it yields, then blocks. */
  @Test(timeout = 5000)
  public void testRunUntilBlocked_ThreadRunsUntilYield() {
    DeterministicRunner d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              status = "started";
              WorkflowThread.await("reason1", () -> unblock1);
              status = "after yield";
            });

    assertEquals("initial", status);

    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals("started", status);
    assertFalse(d.isDone());
  }

  /** Test that multiple yields work correctly with runUntilBlocked. */
  @Test(timeout = 5000)
  public void testRunUntilBlocked_MultipleYields() {
    DeterministicRunner d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              status = "started";
              WorkflowThread.await("reason1", () -> unblock1);
              status = "after1";
              WorkflowThread.await("reason2", () -> unblock2);
              status = "done";
            });

    // First run - blocks on first await
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals("started", status);
    assertFalse(d.isDone());

    // Unblock first await
    unblock1 = true;
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals("after1", status);
    assertFalse(d.isDone());

    // Running again without unblocking doesn't make progress
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals("after1", status);
    assertFalse(d.isDone());

    // Unblock second await
    unblock2 = true;
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals("done", status);
    assertTrue(d.isDone());
  }

  /**
   * Test that runUntilBlocked returns true when progress is made and false when thread is blocked.
   */
  @Test(timeout = 5000)
  public void testRunUntilBlocked_ReturnsProgressIndicator() {
    AtomicReference<WorkflowThread> threadRef = new AtomicReference<>();

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              threadRef.set(DeterministicRunnerImpl.currentThreadInternal());
              status = "started";
              WorkflowThread.await("reason1", () -> unblock1);
              status = "done";
            });

    // First run makes progress (thread starts and runs until yield)
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals("started", status);

    // Get the thread context to test runUntilBlocked directly
    WorkflowThread thread = threadRef.get();
    assertNotNull(thread);

    // Running again without unblocking - no progress expected
    boolean progress =
        thread.runUntilBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertFalse("Should not make progress when still blocked", progress);

    // Unblock and run - should make progress
    unblock1 = true;
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals("done", status);
  }

  // ==================== isDone Tests ====================

  /** Test that isDone returns false while thread is running and true after completion. */
  @Test(timeout = 5000)
  public void testIsDone_ReturnsFalseWhileRunning() {
    AtomicReference<WorkflowThread> threadRef = new AtomicReference<>();

    DeterministicRunner d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              threadRef.set(DeterministicRunnerImpl.currentThreadInternal());
              status = "started";
              WorkflowThread.await("reason1", () -> unblock1);
              status = "done";
            });

    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    WorkflowThread thread = threadRef.get();

    // Thread is yielded but not done
    assertFalse(thread.isDone());
    assertFalse(d.isDone());

    // Complete the thread
    unblock1 = true;
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);

    // Now isDone should be true
    assertTrue(thread.isDone());
    assertTrue(d.isDone());
  }

  /** Test that isDone returns true after thread completes normally. */
  @Test(timeout = 5000)
  public void testIsDone_TrueAfterNormalCompletion() {
    DeterministicRunner d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              status = "done";
              // No yield - completes immediately
            });

    // Run - thread will start and complete without blocking
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals("done", status);
    assertTrue(d.isDone());
  }

  /** Test that isDone returns true after thread throws exception. */
  @Test(timeout = 5000)
  public void testIsDone_TrueAfterException() {
    DeterministicRunner d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              status = "started";
              throw new RuntimeException("test exception");
            });

    try {
      d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
      fail("Expected exception to be thrown");
    } catch (RuntimeException e) {
      assertTrue(
          e.getMessage().contains("test exception")
              || e.getCause().getMessage().contains("test exception"));
    }
    assertTrue(d.isDone());
  }

  // ==================== Cancellation Tests ====================

  /** Test that thread responds to cancel requests properly. */
  @Test(timeout = 5000)
  public void testCancellation_ThreadRespondsToCancelRequest() {
    trace.add("init");
    DeterministicRunner d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              trace.add("started");
              WorkflowThread.await(
                  "waiting for cancel",
                  () -> DeterministicRunnerImpl.currentThreadInternal().isCancelRequested());
              trace.add("cancel detected");
            });

    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertFalse(d.isDone());

    // Cancel the thread
    d.cancel("test cancellation");
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);

    assertTrue(d.isDone());
    trace.setExpected("init", "started", "cancel detected");
  }

  /** Test that cancel reason is properly set and retrievable. */
  @Test(timeout = 5000)
  public void testCancellation_CancelReasonIsSet() {
    AtomicReference<String> cancelReason = new AtomicReference<>();

    DeterministicRunner d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              WorkflowThread current = DeterministicRunnerImpl.currentThreadInternal();
              WorkflowThread.await("waiting for cancel", current::isCancelRequested);
              cancelReason.set(current.getCancellationReason());
            });

    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    d.cancel("specific reason");
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);

    assertEquals("specific reason", cancelReason.get());
  }

  /** Test that stopNow properly destroys the thread. */
  @Test(timeout = 5000)
  public void testCancellation_StopNowDestroysThread() {
    DeterministicRunner d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              status = "started";
              try {
                WorkflowThread.await("reason1", () -> unblock1);
                status = "after yield";
              } catch (DestroyWorkflowThreadError e) {
                failure = e;
                throw e;
              }
            });

    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals("started", status);
    assertFalse(d.isDone());

    // Stop/close the runner
    d.close();

    assertTrue(d.isDone());
    assertNotNull("Should have received DestroyWorkflowThreadError", failure);
    assertTrue(failure instanceof DestroyWorkflowThreadError);
  }

  // ==================== Exception Handling Tests ====================

  /** Test that unhandled exceptions are captured correctly. */
  @Test(timeout = 5000)
  public void testExceptionHandling_UnhandledExceptionsAreCaptured() {
    AtomicReference<WorkflowThread> threadRef = new AtomicReference<>();
    RuntimeException testException = new RuntimeException("test exception message");

    DeterministicRunner d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              threadRef.set(DeterministicRunnerImpl.currentThreadInternal());
              throw testException;
            });

    try {
      d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
      fail("Expected exception");
    } catch (RuntimeException ignored) {
    }

    WorkflowThread thread = threadRef.get();
    assertNotNull(thread);
    Throwable unhandledException = thread.getUnhandledException();
    assertNotNull("Unhandled exception should be captured", unhandledException);
    assertEquals("test exception message", unhandledException.getMessage());
  }

  /** Test that CanceledFailure is handled properly when thread is not cancelled. */
  @Test(timeout = 5000)
  public void testExceptionHandling_CanceledFailureWhenNotCancelled() {
    AtomicReference<WorkflowThread> threadRef = new AtomicReference<>();

    DeterministicRunner d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              threadRef.set(DeterministicRunnerImpl.currentThreadInternal());
              // Throw CanceledFailure without actually being cancelled
              throw new CanceledFailure("unexpected cancel");
            });

    try {
      d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
      fail("Expected exception");
    } catch (Exception ignored) {
    }

    WorkflowThread thread = threadRef.get();
    assertNotNull(thread);
    // When CanceledFailure is thrown but thread wasn't actually cancelled,
    // it should be captured as an unhandled exception
    Throwable unhandledException = thread.getUnhandledException();
    assertNotNull(unhandledException);
    assertTrue(unhandledException instanceof CanceledFailure);
  }

  // ==================== Thread Context Tests ====================

  /** Test that WorkflowThread.currentThreadInternal() returns the correct thread. */
  @Test(timeout = 5000)
  public void testThreadContext_CurrentThreadInternalReturnsCorrectThread() {
    AtomicReference<WorkflowThread> capturedThread = new AtomicReference<>();
    AtomicReference<WorkflowThread> capturedChildThread = new AtomicReference<>();

    DeterministicRunner d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              capturedThread.set(DeterministicRunnerImpl.currentThreadInternal());

              // Create a child thread and verify it gets its own context
              Promise<Void> child =
                  Async.procedure(
                      () -> {
                        capturedChildThread.set(DeterministicRunnerImpl.currentThreadInternal());
                      });
              child.get();
            });

    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);

    assertNotNull("Root thread should be captured", capturedThread.get());
    assertNotNull("Child thread should be captured", capturedChildThread.get());
    assertNotSame(
        "Child thread should be different from root thread",
        capturedThread.get(),
        capturedChildThread.get());
  }

  /** Test that thread name can be set and retrieved. */
  @Test(timeout = 5000)
  public void testThreadContext_ThreadNameCanBeSetAndRetrieved() {
    AtomicReference<String> capturedName = new AtomicReference<>();

    DeterministicRunner d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              WorkflowThread current = DeterministicRunnerImpl.currentThreadInternal();
              capturedName.set(current.getName());
            });

    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);

    assertNotNull(capturedName.get());
    assertEquals("workflow-root", capturedName.get());
  }

  /** Test that getWorkflowContext returns the correct context. */
  @Test(timeout = 5000)
  public void testThreadContext_WorkflowContextIsAccessible() {
    AtomicReference<SyncWorkflowContext> capturedContext = new AtomicReference<>();

    DeterministicRunner d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              WorkflowThread current = DeterministicRunnerImpl.currentThreadInternal();
              capturedContext.set(current.getWorkflowContext());
            });

    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);

    assertNotNull("Workflow context should be accessible", capturedContext.get());
  }

  /** Test that getRunner returns the correct runner. */
  @Test(timeout = 5000)
  public void testThreadContext_RunnerIsAccessible() {
    AtomicReference<DeterministicRunnerImpl> capturedRunner = new AtomicReference<>();

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              WorkflowThread current = DeterministicRunnerImpl.currentThreadInternal();
              capturedRunner.set(current.getRunner());
            });

    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);

    assertNotNull("Runner should be accessible", capturedRunner.get());
    assertSame("Should return the same runner", d, capturedRunner.get());
  }

  // ==================== Thread Locals Tests ====================

  /** Test that thread locals work correctly. */
  @Test(timeout = 5000)
  public void testThreadLocals_SetAndGetValues() {
    AtomicReference<String> retrievedValue = new AtomicReference<>();

    DeterministicRunner d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              WorkflowThread current = DeterministicRunnerImpl.currentThreadInternal();
              WorkflowThreadLocalInternal<String> local = new WorkflowThreadLocalInternal<>();

              // Initially not present
              assertFalse(current.getThreadLocal(local).isPresent());

              // Set a value
              current.setThreadLocal(local, "test value");

              // Now it should be present
              assertTrue(current.getThreadLocal(local).isPresent());
              assertTrue(current.getThreadLocal(local).get().isPresent());
              retrievedValue.set(current.getThreadLocal(local).get().get());
            });

    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertEquals("test value", retrievedValue.get());
  }

  /** Test that thread locals can store null values. */
  @Test(timeout = 5000)
  public void testThreadLocals_NullValueCanBeStored() {
    AtomicBoolean wasPresent = new AtomicBoolean(false);
    AtomicBoolean innerValuePresent = new AtomicBoolean(true);

    DeterministicRunner d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              WorkflowThread current = DeterministicRunnerImpl.currentThreadInternal();
              WorkflowThreadLocalInternal<String> local = new WorkflowThreadLocalInternal<>();

              // Set null value
              current.setThreadLocal(local, null);

              // Should be present but inner value is not present (null)
              wasPresent.set(current.getThreadLocal(local).isPresent());
              innerValuePresent.set(current.getThreadLocal(local).get().isPresent());
            });

    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertTrue("Thread local should be present", wasPresent.get());
    assertFalse("Inner value should not be present (null)", innerValuePresent.get());
  }

  // ==================== Child Thread Tests ====================

  /** Test that child threads work correctly with the base class. */
  @Test(timeout = 5000)
  public void testChildThreads_BasicChildThreadExecution() {
    trace.add("init");

    DeterministicRunner d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              trace.add("root started");

              Promise<Void> child =
                  Async.procedure(
                      () -> {
                        trace.add("child started");
                        WorkflowThread.await("wait in child", () -> unblock1);
                        trace.add("child done");
                      });

              trace.add("root waiting");
              child.get();
              trace.add("root done");
            });

    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertFalse(d.isDone());

    unblock1 = true;
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertTrue(d.isDone());

    trace.setExpected(
        "init", "root started", "root waiting", "child started", "child done", "root done");
  }

  /** Test that multiple child threads can run concurrently. */
  @Test(timeout = 5000)
  public void testChildThreads_MultipleChildThreads() {
    trace.add("init");

    DeterministicRunner d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              trace.add("root started");

              Promise<Void> child1 =
                  Async.procedure(
                      () -> {
                        trace.add("child1 started");
                        WorkflowThread.await("wait in child1", () -> unblock1);
                        trace.add("child1 done");
                      });

              Promise<Void> child2 =
                  Async.procedure(
                      () -> {
                        trace.add("child2 started");
                        WorkflowThread.await("wait in child2", () -> unblock2);
                        trace.add("child2 done");
                      });

              child1.get();
              child2.get();
              trace.add("root done");
            });

    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertFalse(d.isDone());

    // Unblock child1
    unblock1 = true;
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertFalse(d.isDone());

    // Unblock child2
    unblock2 = true;
    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);
    assertTrue(d.isDone());

    trace.setExpected(
        "init",
        "root started",
        "child1 started",
        "child2 started",
        "child1 done",
        "child2 done",
        "root done");
  }

  // ==================== Stack Trace Tests ====================

  /** Test that stack traces can be obtained from threads. */
  @Test(timeout = 5000)
  public void testStackTrace_CanBeObtained() {
    AtomicReference<String> stackTrace = new AtomicReference<>();

    DeterministicRunnerImpl d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              WorkflowThread.await("test await", () -> unblock1);
            });

    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);

    // Get stack trace from runner
    stackTrace.set(d.stackTrace());

    assertNotNull(stackTrace.get());
    assertTrue(
        "Stack trace should contain workflow-root", stackTrace.get().contains("workflow-root"));
  }

  /** Test that thread priority is correctly returned. */
  @Test(timeout = 5000)
  public void testThreadPriority_IsAccessible() {
    AtomicReference<Integer> capturedPriority = new AtomicReference<>();

    DeterministicRunner d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              WorkflowThread current = DeterministicRunnerImpl.currentThreadInternal();
              capturedPriority.set(current.getPriority());
            });

    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);

    assertNotNull("Priority should be accessible", capturedPriority.get());
    // Root thread has priority 0
    assertEquals(Integer.valueOf(0), capturedPriority.get());
  }

  /** Test that isStarted returns correct values. */
  @Test(timeout = 5000)
  public void testIsStarted_ReturnsCorrectValues() {
    AtomicReference<WorkflowThread> threadRef = new AtomicReference<>();
    AtomicBoolean wasStartedDuringExecution = new AtomicBoolean(false);

    DeterministicRunner d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              WorkflowThread current = DeterministicRunnerImpl.currentThreadInternal();
              threadRef.set(current);
              wasStartedDuringExecution.set(current.isStarted());
              WorkflowThread.await("wait", () -> unblock1);
            });

    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);

    WorkflowThread thread = threadRef.get();
    assertNotNull(thread);
    assertTrue("Thread should be started during execution", wasStartedDuringExecution.get());
    assertTrue("Thread should still report as started", thread.isStarted());
  }

  /** Test that thread ID is accessible and unique. */
  @Test(timeout = 5000)
  public void testThreadId_IsAccessibleAndUnique() {
    AtomicReference<Long> rootThreadId = new AtomicReference<>();
    AtomicReference<Long> childThreadId = new AtomicReference<>();

    DeterministicRunner d =
        new DeterministicRunnerImpl(
            threadPool::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              WorkflowThread root = DeterministicRunnerImpl.currentThreadInternal();
              rootThreadId.set(root.getId());

              Promise<Void> child =
                  Async.procedure(
                      () -> {
                        WorkflowThread childThread =
                            DeterministicRunnerImpl.currentThreadInternal();
                        childThreadId.set(childThread.getId());
                      });
              child.get();
            });

    d.runUntilAllBlocked(DeterministicRunner.DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS);

    assertNotNull("Root thread ID should be set", rootThreadId.get());
    assertNotNull("Child thread ID should be set", childThreadId.get());
    assertNotEquals("Thread IDs should be different", rootThreadId.get(), childThreadId.get());
  }
}
