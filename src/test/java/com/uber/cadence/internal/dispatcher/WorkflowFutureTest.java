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

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;

public class WorkflowFutureTest {

    private long currentTime;
    private List<String> trace = new ArrayList<>();

    @Before
    public void setUp() {
        currentTime = 10;
        trace.clear();
    }

    @Test
    public void testFailure() throws Throwable {
        DeterministicRunner r = DeterministicRunner.newRunner(() -> {
            WorkflowFuture<Boolean> f = new WorkflowFuture<>();
            trace.add("root begin");
            Workflow.newThread(() -> f.completeExceptionally(new IllegalArgumentException("foo"))).start();
            Workflow.newThread(() -> {
                try {
                    f.get();
                    trace.add("thread1 get success");
                    fail("failure expected");
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } catch (ExecutionException e) {
                    assertEquals(IllegalArgumentException.class, e.getCause().getClass());
                    trace.add("thread1 get failure");
                }
            }).start();
            trace.add("root done");
        });
        r.runUntilAllBlocked();
        String[] expected = new String[]{
                "root begin",
                "root done",
                "thread1 get failure",
        };
        assertTrace(expected, trace);
    }

    @Test
    public void testCancellation() throws Throwable {
        DeterministicRunner r = DeterministicRunner.newRunner(() -> {
            WorkflowFuture<Boolean> f = new WorkflowFuture<>((ff, i) -> {
                ff.completeExceptionally(new CancellationException());
                trace.add("cancellation handler done");
            });
            trace.add("root begin");
            Workflow.newThread(() -> {
                    f.cancel(true);
                    trace.add("thread1 done");
            }).start();
            trace.add("root done");
        });
        r.runUntilAllBlocked();
        String[] expected = new String[]{
                "root begin",
                "root done",
                "cancellation handler done",
                "thread1 done",
        };
        assertTrace(expected, trace);
    }

    @Test
    public void testGetTimeout() throws Throwable {
        DeterministicRunner r = DeterministicRunner.newRunner(
                null,
                () -> currentTime,
                () -> {
                    WorkflowFuture<String> f = new WorkflowFuture<>();
                    trace.add("root begin");
                    Workflow.newThread(() -> {
                        trace.add("thread1 begin");
                        try {
                            assertEquals("bar", f.get(10, TimeUnit.SECONDS));
                            trace.add("thread1 get success");
                            fail("failure expected");
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        } catch (ExecutionException e) {
                            assertEquals(IllegalArgumentException.class, e.getCause().getClass());
                            trace.add("thread1 get failure");
                        } catch (TimeoutException e) {
                            trace.add("thread1 get timeout");
                        }
                    }).start();
                    trace.add("root done");
                });
        r.runUntilAllBlocked();
        String[] expected = new String[]{
                "root begin",
                "root done",
                "thread1 begin",
        };
        assertTrace(expected, trace);

        currentTime += 11000;
        r.runUntilAllBlocked();
        expected = new String[]{
                "root begin",
                "root done",
                "thread1 begin",
                "thread1 get timeout",
        };
        assertTrace(expected, trace);
    }

    @Test
    public void testMultiple() throws Throwable {
        DeterministicRunner r = DeterministicRunner.newRunner(() -> {
            trace.add("root begin");
            WorkflowFuture<Boolean> f1 = new WorkflowFuture<>();
            WorkflowFuture<Boolean> f2 = new WorkflowFuture<>();
            WorkflowFuture<Boolean> f3 = new WorkflowFuture<>();

            Workflow.newThread(
                    () -> {
                        trace.add("thread1 begin");
                        try {
                            assertTrue(f1.get());
                            trace.add("thread1 f1");
                            f2.complete(true);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        } catch (ExecutionException e) {
                            throw new RuntimeException(e);
                        }
                        trace.add("thread1 done");
                    }
            ).start();
            Workflow.newThread(
                    () -> {
                        trace.add("thread2 begin");
                        try {
                            assertTrue(f2.get());
                            trace.add("thread2 f2");
                            f3.complete(true);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        } catch (ExecutionException e) {
                            throw new RuntimeException(e);
                        }
                        trace.add("thread2 done");
                    }
            ).start();
            f1.complete(true);
            assertFalse(f1.complete(false));
            trace.add("root before f3");
            try {
                assertTrue(f3.get());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
            trace.add("root done");
        });
        r.runUntilAllBlocked();
        String[] expected = new String[]{
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

        assertTrace(expected, trace);
    }

    void assertTrace(String[] expected, List<String> trace) {
        assertEquals(Arrays.asList(expected), trace);
    }

}
