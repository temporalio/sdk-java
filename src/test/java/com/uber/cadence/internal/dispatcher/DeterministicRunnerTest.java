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

import com.uber.cadence.workflow.Functions;
import com.uber.cadence.workflow.WorkflowThread;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
            try {
                WorkflowThreadInternal.yield("reason1",
                        () -> unblock1
                );
                status = "after1";
                WorkflowThreadInternal.yield("reason2",
                        () -> unblock2
                );
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
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
                    try {
                        WorkflowThread.sleep(60000);
                        status = "afterSleep1";
                        WorkflowThread.sleep(60000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
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
            try {
                WorkflowThreadInternal.yield("reason1",
                        () -> unblock1
                );
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
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
            try {
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
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
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
    public void testRootSelfInterrupt() throws Throwable {
        status = "initial";
        DeterministicRunner d = new DeterministicRunnerImpl(() -> {
            status = "started";
            WorkflowThread.currentThread().interrupt();
            try {
                WorkflowThreadInternal.yield("reason1",
                        () -> unblock1
                );
            } catch (InterruptedException e) {
                if (WorkflowThread.interrupted()) {
                    status = "still interrupted";
                } else {
                    status = "interrupted";
                }
            }
        });
        d.runUntilAllBlocked();
        assertTrue(d.isDone());
        assertEquals("interrupted", status);
    }

    @Test
    public void testChild() throws Throwable {
        DeterministicRunner d = new DeterministicRunnerImpl(() -> {
            WorkflowThread thread = WorkflowInternal.newThread(() -> {
                status = "started";
                try {
                    WorkflowThreadInternal.yield("reason1",
                            () -> unblock1
                    );
                    status = "after1";
                    WorkflowThreadInternal.yield("reason2",
                            () -> unblock2
                    );
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                status = "done";
            });
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
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
    public void testChildInterrupt() throws Throwable {
        DeterministicRunner d = new DeterministicRunnerImpl(() -> {
            trace.add("root started");
            WorkflowThread thread = WorkflowInternal.newThread(() -> {
                trace.add("child started");
                try {
                    WorkflowThreadInternal.yield("reason1",
                            () -> unblock1
                    );
                    trace.add("child after1");
                    WorkflowThreadInternal.yield("reason2",
                            () -> unblock2
                    );
                } catch (InterruptedException e) {
                    // Set to false when exception was thrown.
                    assertFalse(Thread.currentThread().isInterrupted());
                    trace.add("child interrupted");
                }
                trace.add("child done");
            });
            thread.start();
            try {
                trace.add("root blocked");
                WorkflowThreadInternal.yield("rootReason1",
                        () -> unblockRoot
                );
                assertFalse(thread.isInterrupted());
                thread.interrupt();
                assertTrue(thread.isInterrupted());
                trace.add("root waiting for join");
                thread.join();
                trace.add("root done");
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        d.runUntilAllBlocked();
        unblock1 = true;
        d.runUntilAllBlocked();
        unblockRoot = true;
        d.runUntilAllBlocked();
        unblock2 = true;
        d.runUntilAllBlocked();
        assertTrue(d.isDone());
        String[] expected = new String[]{
                "root started",
                "root blocked",
                "child started",
                "child after1",
                "root waiting for join",
                "child interrupted",
                "child done",
                "root done"
        };
        assertTrace(expected, trace);
    }

    @Test
    public void testJoinTimeout() throws Throwable {
        DeterministicRunnerImpl d = new DeterministicRunnerImpl(
                threadPool,
                null,
                () -> currentTime, // clock override
                () -> {
                    trace.add("root started");

                    WorkflowThread thread = WorkflowInternal.newThread(() -> {
                        trace.add("child started");
                        try {
                            WorkflowThreadInternal.yield("blockForever",
                                    () -> false
                            );
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        trace.add("child done");
                    });
                    thread.start();
                    try {
                        thread.join(60000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
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

    void assertTrace(String[] expected, List<String> trace) {
        assertEquals(Arrays.asList(expected), trace);
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
            WorkflowThread thread = WorkflowInternal.newThread(new TestChildTreeRunnable(depth + 1));
            thread.start();
            try {
                WorkflowThreadInternal.yield("reason1",
                        () -> unblock1
                );
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            try {
                thread.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
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