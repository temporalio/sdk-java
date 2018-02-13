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

import com.uber.cadence.workflow.CancellationScope;
import com.uber.cadence.workflow.RFuture;
import com.uber.cadence.workflow.WFuture;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowThread;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class DeterministicRunnerTest {

    private String status;
    private boolean unblock1;
    private boolean unblock2;
    private boolean unblockRoot;
    private Throwable failure;
    List<String> trace = new ArrayList<>();
    private long currentTime;
    private ExecutorService threadPool;

    @Before
    public void setUp() {
        unblock1 = false;
        unblock2 = false;
        unblockRoot = false;
        failure = null;
        status = "initial";
        trace.clear();
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
        DeterministicRunner d = new DeterministicRunnerImpl(() -> {
            status = "started";
            WorkflowThreadInternal.yield("reason1",
                    () -> unblock1
            );
            status = "after1";
            WorkflowThreadInternal.yield("reason2",
                    () -> unblock2
            );
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

    @Test
    public void testSleep() throws Throwable {
        DeterministicRunnerImpl d = new DeterministicRunnerImpl(
                threadPool,
                null,
                () -> currentTime, // clock override
                () -> {
                    status = "started";
                    WorkflowThread.sleep(60000);
                    status = "afterSleep1";
                    WorkflowThread.sleep(60000);
                    status = "done";
                });
        currentTime = 1000;
        assertEquals("initial", status);
        assertEquals(1000, d.currentTimeMillis());
        d.runUntilAllBlocked();
        currentTime = 20000;
        assertEquals("started", status);
        assertEquals(20000, d.currentTimeMillis());
        d.runUntilAllBlocked();
        assertEquals("started", status);
        assertFalse(d.isDone());

        currentTime = 70000; // unblocks first sleep
        d.runUntilAllBlocked();
        assertEquals("afterSleep1", status);
        // Just check that running again doesn't make any progress.
        d.runUntilAllBlocked();
        assertEquals("afterSleep1", status);
        assertEquals(70000, d.currentTimeMillis());

        currentTime = 200000; // unblock second sleep
        d.runUntilAllBlocked();
        assertEquals("done", status);
        assertTrue(d.isDone());
    }

    @Test
    public void testRootFailure() throws Throwable {
        DeterministicRunner d = new DeterministicRunnerImpl(() -> {
            status = "started";
            WorkflowThreadInternal.yield("reason1",
                    () -> unblock1
            );
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
        } catch (Throwable throwable) {
        }
        assertTrue(d.isDone());
    }

    @Test
    public void testDispatcherStop() throws Throwable {
        DeterministicRunner d = new DeterministicRunnerImpl(() -> {
            status = "started";
            WorkflowThreadInternal.yield("reason1",
                    () -> unblock1
            );
            status = "after1";
            try {
                WorkflowThreadInternal.yield("reason2",
                        () -> unblock2
                );
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
        DeterministicRunner d = new DeterministicRunnerImpl(() -> {
            trace.add("root started");
            WorkflowThread thread1 = WorkflowInternal.newThread(false, () -> {
                trace.add("child1 started");
                WorkflowThreadInternal.yield("reason1",
                        () -> unblock1
                );
                trace.add("child1 done");
            });
            WorkflowThread thread2 = WorkflowInternal.newThread(false, () -> {
                trace.add("child2 started");
                WorkflowThreadInternal.yield("reason2",
                        () -> unblock2
                );
                trace.add("child2 exiting");
                WorkflowThreadInternal.exit("exitValue");
                trace.add("child2 done");
            });
            thread1.start();
            thread2.start();
            thread1.join();
            thread2.join();
            trace.add("root done");
        });
        d.runUntilAllBlocked();
        assertFalse(d.isDone());
        unblock2 = true;
        d.runUntilAllBlocked();
        assertTrue(d.isDone());
        assertEquals("exitValue", d.getExitValue());
        String[] expected = new String[]{
                "root started",
                "child1 started",
                "child2 started",
                "child2 exiting",
        };
        assertTrace(expected, trace);

    }

    @Test
    public void testRootCancellation() throws Throwable {
        trace.add("init");
        DeterministicRunner d = new DeterministicRunnerImpl(() -> {
            trace.add("root started");
            WorkflowThreadInternal.yield("reason1",
                    () -> CancellationScope.current().isCancelRequested()
            );
            trace.add("second yield: " + CancellationScope.current().getCancellationReason());
            WorkflowThreadInternal.yield("reason1",
                    () -> CancellationScope.current().isCancelRequested()
            );
            trace.add("root done");
        });
        d.runUntilAllBlocked();
        assertFalse(d.isDone());
        d.cancel("I just feel like it");
        d.runUntilAllBlocked();
        assertTrue(d.isDone());
        String[] expected = new String[]{
                "init",
                "root started",
                "second yield: I just feel like it",
                "root done",
        };
        assertTrace(expected, trace);
    }

    @Test
    public void testExplicitScopeCancellation() throws Throwable {
        trace.add("init");
        DeterministicRunner d = new DeterministicRunnerImpl(() -> {
            trace.add("root started");
            WFuture<Void> var = Workflow.newFuture();
            CancellationScope scope = Workflow.newCancellationScope(() -> {
                trace.add("scope started");
                var.completeFrom(newTimer(300));
                trace.add("scope done");
            });
            trace.add("root before cancel");
            scope.cancel("from root");
            try {
                var.get();
                trace.add("after get");
            } catch (CancellationException e) {
                trace.add("scope cancelled");
            }
            trace.add("root done");
        });
        d.runUntilAllBlocked();
        assertTrue(trace.toString(), d.isDone());
        String[] expected = new String[]{
                "init",
                "root started",
                "scope started",
                "scope done",
                "root before cancel",
                "timer cancelled",
                "scope cancelled",
                "root done",
        };
        assertTrace(expected, trace);
    }

    @Test
    public void testExplicitDetachedScopeCancellation() throws Throwable {
        trace.add("init");
        DeterministicRunner d = new DeterministicRunnerImpl(() -> {
            trace.add("root started");
            WFuture<Void> var = Workflow.newFuture();
            CancellationScope scope = Workflow.newDetachedCancellationScope(() -> {
                trace.add("scope started");
                var.completeFrom(newTimer(300));
                trace.add("scope done");
            });
            trace.add("root before cancel");
            scope.cancel("from root");
            try {
                var.get();
                trace.add("after get");
            } catch (CancellationException e) {
                trace.add("scope cancelled");
            }
            trace.add("root done");
        });
        d.runUntilAllBlocked();
        assertTrue(trace.toString(), d.isDone());
        String[] expected = new String[]{
                "init",
                "root started",
                "scope started",
                "scope done",
                "root before cancel",
                "timer cancelled",
                "scope cancelled",
                "root done",
        };
        assertTrace(expected, trace);
    }

    private RFuture<Void> newTimer(int milliseconds) {
        WFuture<Void> result = Workflow.newFuture();
        Workflow.newThread(() -> {
            try {
                WorkflowThread.sleep(milliseconds);
                result.complete(null);
                trace.add("timer fired");
            } catch (CancellationException e) {
                trace.add("timer cancelled");
                result.completeExceptionally(e);
            }
        }).start();
        return result;
    }

    @Test
    public void testExplicitThreadCancellation() throws Throwable {
        trace.add("init");
        DeterministicRunner d = new DeterministicRunnerImpl(() -> {
            trace.add("root started");
            WorkflowThread thread1 = Workflow.newThread(() -> {
                trace.add("thread started");
                WFuture<String> cancellation = CancellationScope.current().getCancellationRequest();
                WorkflowThreadInternal.yield("reason1",
                        () -> CancellationScope.current().isCancelRequested()
                );
                trace.add("thread done: " + cancellation.get());
            });
            thread1.start();
            trace.add("root before cancel");
            thread1.cancel("from root");
            thread1.join();
            trace.add("root done");
        });
        d.runUntilAllBlocked();
        assertTrue(d.stackTrace(), d.isDone());
        String[] expected = new String[]{
                "init",
                "root started",
                "root before cancel",
                "thread started",
                "thread done: from root",
                "root done",
        };
        assertTrace(expected, trace);
    }

    @Test
    public void testDetachedCancellation() throws Throwable {
        trace.add("init");
        DeterministicRunner d = new DeterministicRunnerImpl(() -> {
            trace.add("root started");
            WorkflowThread thread1 = Workflow.newDetachedThread(() -> {
                trace.add("thread started");
                WorkflowThreadInternal.yield("reason1",
                        () -> unblock1 || CancellationScope.current().isCancelRequested()
                );
                trace.add("yield done");
            });
            thread1.start();
            try {
                thread1.join();
            } catch (CancellationException e) {
                trace.add("join cancelled");
            }
            trace.add("root done");
        });
        d.runUntilAllBlocked();
        assertFalse(trace.toString(), d.isDone());
        d.cancel("I just feel like it");
        d.runUntilAllBlocked();
        assertFalse(d.isDone());
        String[] expected = new String[]{
                "init",
                "root started",
                "thread started",
        };
        assertTrace(expected, trace);
        unblock1 = true;
        d.runUntilAllBlocked();
        assertTrue(d.isDone());
        expected = new String[]{
                "init",
                "root started",
                "thread started",
                "yield done",
                "root done",
        };
        assertTrace(expected, trace);
    }

    @Test
    public void testChild() throws Throwable {
        DeterministicRunner d = new DeterministicRunnerImpl(() -> {
            WorkflowThread thread = WorkflowInternal.newThread(false, () -> {
                status = "started";
                WorkflowThreadInternal.yield("reason1",
                        () -> unblock1
                );
                status = "after1";
                WorkflowThreadInternal.yield("reason2",
                        () -> unblock2
                );
                status = "done";
            });
            thread.start();
            thread.join();
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

    @Test
    public void testJoinTimeout() throws Throwable {
        DeterministicRunnerImpl d = new DeterministicRunnerImpl(
                threadPool,
                null,
                () -> currentTime, // clock override
                () -> {
                    trace.add("root started");

                    WorkflowThread thread = WorkflowInternal.newThread(false, () -> {
                        trace.add("child started");
                        WorkflowThreadInternal.yield("blockForever",
                                () -> false
                        );
                        trace.add("child done");
                    });
                    thread.start();
                    thread.join(60000);
                    trace.add("root done");
                });
        currentTime = 1000;
        d.runUntilAllBlocked();
        assertEquals(61000, d.getNextWakeUpTime());
        assertFalse(d.isDone());
        String[] expected = new String[]{
                "root started",
                "child started",
        };
        assertTrace(expected, trace);
        // Just check that running again doesn't make any progress.
        d.runUntilAllBlocked();
        assertEquals(61000, d.getNextWakeUpTime());
        currentTime = 70000;
        d.runUntilAllBlocked();
        assertFalse(d.isDone());
        expected = new String[]{
                "root started",
                "child started",
                "root done"
        };
        assertTrace(expected, trace);
        d.close();
    }

    private void assertTrace(String[] expected, List<String> trace) {
        assertEquals(Arrays.asList(expected), trace);
    }

    private static final int CHILDREN = 10;

    private class TestChildTreeRunnable implements Runnable {
        final int depth;

        private TestChildTreeRunnable(int depth) {
            this.depth = depth;
        }

        @Override
        public void run() {
            trace.add("child " + depth + " started");
            if (depth >= CHILDREN) {
                trace.add("child " + depth + " done");
                return;
            }
            WorkflowThread thread = WorkflowInternal.newThread(false,
                    new TestChildTreeRunnable(depth + 1));
            thread.start();
            WorkflowThreadInternal.yield("reason1",
                    () -> unblock1
            );
            thread.join();
            trace.add("child " + depth + " done");
        }
    }

    @Test
    public void testChildTree() throws Throwable {
        DeterministicRunner d = new DeterministicRunnerImpl(new TestChildTreeRunnable(0));
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
        assertEquals(expected, trace);
    }
}