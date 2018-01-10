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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;

public class WorkflowQueueTest {

    private long currentTime;
    private List<String> trace = new ArrayList<>();

    @Before
    public void setUp() {
        currentTime = 10;
        trace.clear();
    }

    @Test
    public void testTakeBlocking() throws Throwable {
        DeterministicRunner r = DeterministicRunner.newRunner(() -> {
            WorkflowQueue<Boolean> f = new WorkflowQueue<>(1);
            trace.add("root begin");
            Workflow.newThread(() -> {
                try {
                    trace.add("thread1 begin");
                    assertTrue(f.take());
                    trace.add("thread1 take success");
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }).start();
            Workflow.newThread(() -> {
                try {
                    trace.add("thread2 begin");
                    f.put(true);
                    trace.add("thread2 put success");
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }).start();
            trace.add("root done");
        });
        r.runUntilAllBlocked();
        String[] expected = new String[]{
                "root begin",
                "root done",
                "thread1 begin",
                "thread2 begin",
                "thread2 put success",
                "thread1 take success",

        };
        assertTrace(expected, trace);
    }

    @Test
    public void testPutBlocking() throws Throwable {
        DeterministicRunner r = DeterministicRunner.newRunner(() -> currentTime, () -> {
            WorkflowQueue<Boolean> f = new WorkflowQueue<>(1);
            trace.add("root begin");
            Workflow.newThread(() -> {
                try {
                    trace.add("thread1 begin");
                    WorkflowThread.sleep(2000);
                    assertTrue(f.take());
                    trace.add("thread1 take1 success");
                    assertFalse(f.take());
                    trace.add("thread1 take2 success");
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }).start();
            Workflow.newThread(() -> {
                try {
                    trace.add("thread2 begin");
                    f.put(true);
                    trace.add("thread2 put1 success");
                    f.put(false);
                    trace.add("thread2 put2 success");
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }).start();
            trace.add("root done");
        });
        r.runUntilAllBlocked();
        currentTime += 3000;
        r.runUntilAllBlocked();
        String[] expected = new String[]{
                "root begin",
                "root done",
                "thread1 begin",
                "thread2 begin",
                "thread2 put1 success",
                "thread1 take1 success",
                "thread2 put2 success",
                "thread1 take2 success",
        };
        assertTrace(expected, trace);
    }

    void assertTrace(String[] expected, List<String> trace) {
        assertEquals(Arrays.asList(expected), trace);
    }

}
