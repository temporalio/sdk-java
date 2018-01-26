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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Execute dining philosophers algorithm twice and check if order of execution is the same
 * under deterministic runner.
 */
public class DiningPhilosophersTest {

    public class Philosopher implements Functions.Proc {

        private final Lock leftFork;
        private final Lock rightFork;

        public Philosopher(Lock leftFork, Lock rightFork) {
            this.leftFork = leftFork;
            this.rightFork = rightFork;
        }

        @Override
        public void apply() {
            try {
                while (true) {

                    // thinking
                    doAction(Workflow.currentTimeMillis() + ": Thinking");
                    leftFork.lock();
                    try {
                        doAction(
                                Workflow.currentTimeMillis()
                                        + ": Picked up left fork");
                        rightFork.lock();
                        try {
                            // eating
                            doAction(
                                    Workflow.currentTimeMillis()
                                            + ": Picked up right fork - eating");

                            doAction(
                                    Workflow.currentTimeMillis()
                                            + ": Put down right fork");
                        } finally {
                            rightFork.unlock();
                        }

                        // Back to thinking
                        doAction(
                                Workflow.currentTimeMillis()
                                        + ": Put down left fork. Back to thinking");
                    } finally {
                        leftFork.unlock();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        private void doAction(String action) throws InterruptedException {
            trace.add(WorkflowThread.currentThread().getName() + " " + action);
            WorkflowThread.sleep(((int) (random.nextDouble() * 100)));
        }
    }

    private class DiningSimulation implements Functions.Proc {

        @Override
        public void apply() {
            Philosopher[] philosophers = new Philosopher[PHYLOSOPHER_COUNT];
            List<Lock> forks = new ArrayList<>(PHYLOSOPHER_COUNT);
            for (int i = 0; i < PHYLOSOPHER_COUNT; i++) {
                forks.add(Workflow.newReentrantLock());
            }

            for (int i = 0; i < philosophers.length; i++) {
                Lock leftFork = forks.get(i);
                Lock rightFork = forks.get((i + 1) % forks.size());


                if (i == philosophers.length - 1) {
                    // The last philosopher picks up the right fork first
                    // to avoid deadlock
                    philosophers[i] = new Philosopher(rightFork, leftFork);
                } else {
                    philosophers[i] = new Philosopher(leftFork, rightFork);
                }

                WorkflowThread t =
                        Workflow.newThread(philosophers[i], "Philosopher " + (i + 1));
                t.start();
            }
        }
    }

    private static final int PHYLOSOPHER_COUNT = 5;

    private List<String> trace;

    private long currentTime = 0;

    private Random random;

    @Test
    public void testExecutionDeterminism() throws Throwable {
        List<String> trace1 = dine();
        assertTrue(trace1.size() > 0);

        List<String> trace2 = dine();
        assertEquals(trace1, trace2);
    }

    private List<String> dine() throws Throwable {
        currentTime = 0;
        trace = new ArrayList<>();
        random = new Random(1234); // Use seeded for determinism
        ExecutorService threadPool = new ThreadPoolExecutor(1, 1000, 1, TimeUnit.SECONDS, new SynchronousQueue<>());
        DeterministicRunner runner = DeterministicRunner.newRunner(
                threadPool,
                null,
                () -> currentTime,
                new DiningSimulation()
        );
        for (int i = 0; i < 10000; i++) {
            currentTime += 10;
            runner.runUntilAllBlocked();
            assertFalse("i=" + i, runner.isDone());
        }
        runner.close();
        threadPool.shutdown();
        threadPool.awaitTermination(1, TimeUnit.MINUTES);
        return trace;
    }
}