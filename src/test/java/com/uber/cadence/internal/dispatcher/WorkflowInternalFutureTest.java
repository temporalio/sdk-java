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

import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowFuture;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;

public class WorkflowInternalFutureTest {

    @Rule
    public final Tracer trace = new Tracer();

    private long currentTime;

    @Before
    public void setUp() {
        currentTime = 10;
    }

    @Test
    public void testFailure() throws Throwable {
        DeterministicRunner r = DeterministicRunner.newRunner(() -> {
            WorkflowFuture<Boolean> f = Workflow.newFuture();
            trace.add("root begin");
            WorkflowInternal.newThread(() -> f.completeExceptionally(new IllegalArgumentException("foo"))).start();
            WorkflowInternal.newThread(() -> {
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
        trace.setExpected(expected);
    }

    @Test
    public void testCancellation() throws Throwable {
        DeterministicRunner r = DeterministicRunner.newRunner(() -> {
            WorkflowFuture<Boolean> f = new WorkflowFutureImpl<>((ff, i) -> {
                ff.completeExceptionally(new CancellationException());
                trace.add("cancellation handler done");
            });
            trace.add("root begin");
            WorkflowInternal.newThread(() -> {
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
        trace.setExpected(expected);
    }

    @Test
    public void testGetTimeout() throws Throwable {
        ExecutorService threadPool = new ThreadPoolExecutor(1, 1000, 1, TimeUnit.SECONDS, new SynchronousQueue<>());

        DeterministicRunner r = DeterministicRunner.newRunner(
                threadPool,
                null,
                () -> currentTime,
                () -> {
                    WorkflowFuture<String> f = Workflow.newFuture();
                    trace.add("root begin");
                    WorkflowInternal.newThread(() -> {
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
        trace.setExpected(expected);
        trace.assertExpected();

        currentTime += 11000;
        r.runUntilAllBlocked();
        expected = new String[]{
                "root begin",
                "root done",
                "thread1 begin",
                "thread1 get timeout",
        };
        trace.setExpected(expected);
        threadPool.shutdown();
        threadPool.awaitTermination(1, TimeUnit.MINUTES);
    }

    @Test
    public void testMultiple() throws Throwable {
        DeterministicRunner r = DeterministicRunner.newRunner(() -> {
            trace.add("root begin");
            WorkflowFuture<Boolean> f1 = Workflow.newFuture();
            WorkflowFuture<Boolean> f2 = Workflow.newFuture();
            WorkflowFuture<Boolean> f3 = Workflow.newFuture();

            WorkflowInternal.newThread(
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
            WorkflowInternal.newThread(
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

        trace.setExpected(expected);
    }
}
