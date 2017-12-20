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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

class LockImpl implements Lock {

    private WorkflowThread owner;
    private int holdCount;

    @Override
    public void lock() {
        boolean interrupted = false;
        do {
            try {
                WorkflowThreadImpl.yield("lock", () -> tryLock());
            } catch (InterruptedException e) {
                interrupted = true;
            }
        } while (interrupted);
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        WorkflowThreadImpl.yield("lockInterruptibly", () -> tryLock());
    }

    @Override
    public boolean tryLock() {
        WorkflowThread currentThread = WorkflowThread.currentThread();
        if (owner == null || owner == currentThread) {
            owner = currentThread;
            holdCount++;
        }
        return owner == currentThread;
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        return WorkflowThreadImpl.yield(unit.toMillis(time), "tryLock", () -> tryLock());
    }

    @Override
    public void unlock() {
        if (owner == null || holdCount == 0) {
            throw new IllegalMonitorStateException("not locked");
        }
        if (owner != WorkflowThread.currentThread()) {
            throw new IllegalMonitorStateException(
                    WorkflowThread.currentThread().getName() + " is not an owner. " +
                            "Owner is " + owner.getName());
        }
        holdCount--;
        owner = null;
    }

    @Override
    public Condition newCondition() {
        return null;
    }
}
