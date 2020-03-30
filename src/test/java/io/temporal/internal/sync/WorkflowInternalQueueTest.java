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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.temporal.workflow.QueueConsumer;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowQueue;
import java.util.concurrent.CancellationException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowInternalQueueTest {

  private long currentTime;
  @Rule public final Tracer trace = new Tracer();

  @Before
  public void setUp() {
    currentTime = 10;
  }

  @Test
  public void testTakeBlocking() throws Throwable {
    DeterministicRunner r =
        DeterministicRunner.newRunner(
            () -> {
              WorkflowQueue<Boolean> f = WorkflowInternal.newQueue(1);
              trace.add("root begin");
              WorkflowInternal.newThread(
                      false,
                      () -> {
                        trace.add("thread1 begin");
                        assertTrue(f.take());
                        trace.add("thread1 take success");
                      })
                  .start();
              WorkflowInternal.newThread(
                      false,
                      () -> {
                        trace.add("thread2 begin");
                        f.put(true);
                        trace.add("thread2 put success");
                      })
                  .start();
              trace.add("root done");
            });
    r.runUntilAllBlocked();
    String[] expected =
        new String[] {
          "root begin",
          "root done",
          "thread1 begin",
          "thread2 begin",
          "thread2 put success",
          "thread1 take success",
        };
    trace.setExpected(expected);
  }

  @Test
  public void testTakeCancelled() throws Throwable {
    DeterministicRunner r =
        DeterministicRunner.newRunner(
            () -> {
              WorkflowQueue<Boolean> f = WorkflowInternal.newQueue(1);
              trace.add("root begin");
              WorkflowInternal.newThread(
                      false,
                      () -> {
                        trace.add("thread1 begin");
                        try {
                          assertTrue(f.take());
                        } catch (CancellationException e) {
                          trace.add("thread1 CancellationException");
                        }
                        trace.add("thread1 done");
                      })
                  .start();
              trace.add("root done");
            });
    r.runUntilAllBlocked();
    r.cancel("test");
    r.runUntilAllBlocked();

    String[] expected =
        new String[] {
          "root begin",
          "root done",
          "thread1 begin",
          "thread1 CancellationException",
          "thread1 done",
        };
    trace.setExpected(expected);
  }

  @Test
  public void testPutBlocking() throws Throwable {
    DeterministicRunner r =
        DeterministicRunner.newRunner(
            () -> currentTime,
            () -> {
              WorkflowQueue<Boolean> f = WorkflowInternal.newQueue(1);
              trace.add("root begin");
              WorkflowInternal.newThread(
                      false,
                      () -> {
                        trace.add("thread1 begin");
                        Workflow.sleep(2000);
                        assertTrue(f.take());
                        trace.add("thread1 take1 success");
                        assertFalse(f.take());
                        trace.add("thread1 take2 success");
                      })
                  .start();
              WorkflowInternal.newThread(
                      false,
                      () -> {
                        trace.add("thread2 begin");
                        f.put(true);
                        trace.add("thread2 put1 success");
                        f.put(false);
                        trace.add("thread2 put2 success");
                      })
                  .start();
              trace.add("root done");
            });
    r.runUntilAllBlocked();
    currentTime += 3000;
    r.runUntilAllBlocked();
    String[] expected =
        new String[] {
          "root begin",
          "root done",
          "thread1 begin",
          "thread2 begin",
          "thread2 put1 success",
          "thread1 take1 success",
          "thread2 put2 success",
          "thread1 take2 success",
        };
    trace.setExpected(expected);
  }

  @Test
  public void testPutCancelled() throws Throwable {
    DeterministicRunner r =
        DeterministicRunner.newRunner(
            () -> {
              WorkflowQueue<Boolean> f = WorkflowInternal.newQueue(1);
              trace.add("root begin");
              WorkflowInternal.newThread(
                      false,
                      () -> {
                        trace.add("thread1 begin");
                        try {
                          f.put(true);
                          f.put(true);
                        } catch (CancellationException e) {
                          trace.add("thread1 CancellationException");
                        }
                        trace.add("thread1 done");
                      })
                  .start();
              trace.add("root done");
            });
    r.runUntilAllBlocked();
    r.cancel("test");
    r.runUntilAllBlocked();

    String[] expected =
        new String[] {
          "root begin",
          "root done",
          "thread1 begin",
          "thread1 CancellationException",
          "thread1 done",
        };
    trace.setExpected(expected);
  }

  @Test
  public void testMap() throws Throwable {
    DeterministicRunner r =
        DeterministicRunner.newRunner(
            () -> {
              WorkflowQueue<Integer> queue = WorkflowInternal.newQueue(1);
              trace.add("root begin");
              WorkflowInternal.newThread(
                      false,
                      () -> {
                        QueueConsumer<String> mapped = queue.map((s) -> s + "-mapped");
                        trace.add("thread1 begin");
                        for (int i = 0; i < 10; i++) {
                          trace.add("thread1 " + mapped.take());
                        }
                        trace.add("thread1 done");
                      })
                  .start();
              trace.add("root thread1 started");
              for (int i = 0; i < 10; i++) {
                queue.put(i);
              }
              trace.add("root done");
            });
    r.runUntilAllBlocked();
    r.cancel("test");
    r.runUntilAllBlocked();

    String[] expected =
        new String[] {
          "root begin",
          "root thread1 started",
          "thread1 begin",
          "thread1 0-mapped",
          "thread1 1-mapped",
          "thread1 2-mapped",
          "thread1 3-mapped",
          "thread1 4-mapped",
          "thread1 5-mapped",
          "thread1 6-mapped",
          "thread1 7-mapped",
          "thread1 8-mapped",
          "root done",
          "thread1 9-mapped",
          "thread1 done",
        };
    trace.setExpected(expected);
  }
}
