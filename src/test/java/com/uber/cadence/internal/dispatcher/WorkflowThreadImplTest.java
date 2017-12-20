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

import org.junit.Test;

import static org.junit.Assert.*;

public class WorkflowThreadImplTest {

    private String status;
    private boolean unblock1;
    private boolean unblock2;
    private Throwable failure;

    @Test
    public void testThread() {
        status = "initial";
        WorkflowThreadImpl c = new WorkflowThreadImpl(null, "test", () -> {
            status = "started";
            try {
                WorkflowThreadImpl.yield("reason1",
                        () -> unblock1
                );
                status = "after1";
                WorkflowThreadImpl.yield("reason2",
                        () -> unblock2
                );
                status = "done";
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        c.start();
        assertEquals("initial", status);
        c.runUntilBlocked();
        assertEquals("started", status);
        assertFalse(c.isDone());
        c.evaluateInCoroutineContext(reason -> assertEquals("reason1", reason));
        unblock1 = true;
        c.runUntilBlocked();
        assertEquals("after1", status);
        c.evaluateInCoroutineContext(reason -> assertEquals("reason2", reason));
        // Just check that running again doesn't make any progress.
        c.runUntilBlocked();
        assertEquals("after1", status);
        c.evaluateInCoroutineContext(reason -> assertEquals("reason2", reason));
        unblock2 = true;
        c.runUntilBlocked();
        assertEquals("done", status);
        assertTrue(c.isDone());
        assertNull(c.getUnhandledException());
    }

    @Test
    public void testThreadFailure() {
        status = "initial";
        WorkflowThreadImpl c = new WorkflowThreadImpl(null,  "test", () -> {
            status = "started";
            try {
                WorkflowThreadImpl.yield("reason1",
                        () -> unblock1
                );
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            throw new RuntimeException("simulated");
        });
        c.start();
        assertEquals("initial", status);
        c.runUntilBlocked();
        assertEquals("started", status);
        assertFalse(c.isDone());
        c.evaluateInCoroutineContext(reason -> assertEquals("reason1", reason));
        unblock1 = true;
        c.runUntilBlocked();
        assertTrue(c.isDone());
        assertNotNull(c.getUnhandledException());
    }

    @Test
    public void testThreadSelfInterrupt() {
        status = "initial";
        WorkflowThreadImpl c = new WorkflowThreadImpl(null,  "test", () -> {
            status = "started";
            WorkflowThread.currentThread().interrupt();
            try {
                WorkflowThreadImpl.yield("reason1",
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
        c.start();
        assertEquals("initial", status);
        c.runUntilBlocked();
        assertTrue(c.isDone());
        assertEquals("interrupted", status);
        assertNull(c.getUnhandledException());
    }

    @Test
    public void testThreadStop() {
        status = "initial";
        WorkflowThreadImpl c = new WorkflowThreadImpl(null,  "test", () -> {
            status = "started";
            try {
                WorkflowThreadImpl.yield("reason1",
                        () -> unblock1
                );
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            status = "after1";
            try {
                WorkflowThreadImpl.yield("reason2",
                        () -> unblock2
                );
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (DestroyWorkflowThreadError e) {
                failure = e;
                throw e;
            }
            status = "done";
        });
        c.start();
        assertEquals("initial", status);
        c.runUntilBlocked();
        assertEquals("started", status);
        assertFalse(c.isDone());
        c.evaluateInCoroutineContext(reason -> assertEquals("reason1", reason));
        unblock1 = true;
        c.runUntilBlocked();
        assertEquals("after1", status);
        c.evaluateInCoroutineContext(reason -> assertEquals("reason2", reason));
        c.stop();
        assertTrue(c.isDone());
        assertNull(c.getUnhandledException());
        assertNotNull(failure);
    }
}