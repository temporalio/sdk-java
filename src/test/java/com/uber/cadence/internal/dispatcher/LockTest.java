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
import java.util.concurrent.locks.Lock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class LockTest {

    private Lock lock;
    private boolean unblock1;
    private boolean unblock2;
    private long currentTime;

    @Before
    public void setUp() {
        lock = Workflow.newReentrantLock();
        unblock1 = false;
        unblock2 = false;
        currentTime = 10;
    }

    @Test
    public void testUnlockFailures() throws Throwable {
        run(() -> {
            assertFailure(IllegalMonitorStateException.class, () -> lock.unlock());
            assertFailure(IllegalMonitorStateException.class, () -> {
                lock.lock();
                lock.lock();
                lock.unlock();
                lock.unlock();
                lock.unlock();
            });
        });
    }

    @Test
    public void testLocking() throws Throwable {
        List<String> trace = new ArrayList<>();
        DeterministicRunner r = DeterministicRunner.newRunner(null, () -> currentTime, () -> {
            trace.add("root begin");
            Workflow.newThread(
                    () -> {
                        lock.lock();
                        trace.add("thread1 lock");
                        try {
                            WorkflowThreadImpl.yield("thread1", () -> unblock1);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        lock.unlock();
                        trace.add("thread1 done");
                    }
            ).start();
            Workflow.newThread(
                    () -> {
                        try {
                            WorkflowThreadImpl.yield("thread2", () -> unblock2);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        lock.lock();
                        trace.add("thread2 lock");

                        lock.unlock();
                        trace.add("thread2 done");
                    }
            ).start();
            unblock2 = true;
            try {
                WorkflowThread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            unblock1 = true;
            trace.add("root done");
        });
        r.runUntilAllBlocked();
        currentTime = 1000;
        r.runUntilAllBlocked();
        String[] expected = new String[]{
                "root begin",
                "thread1 lock",
                "root done",
                "thread1 done",
                "thread2 lock",
                "thread2 done"
        };
        assertTrace(expected, trace);
    }


    private void assertFailure(Class<? extends Throwable> failure, Runnable runnable) {
        try {
            lock.unlock();
            fail("failure of " + failure.getName() + " expected");
        } catch (Exception e) {
            if (!failure.isAssignableFrom(e.getClass())) {
                fail("failure of " + failure.getName() + " expected instead of " + e.toString());
            }
        }
    }

    private void run(Runnable runnable) throws Throwable {
        DeterministicRunner r = DeterministicRunner.newRunner(runnable);
        r.runUntilAllBlocked();
        r.close();
    }

    void assertTrace(String[] expected, List<String> trace) {
        assertEquals(Arrays.asList(expected), trace);
    }


}
