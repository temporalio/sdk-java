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

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

public final class WorkflowQueue<E> implements WorkflowQueueConsumer<E>, WorkflowQueueProducer<E> {

    private final Queue<E> queue = new LinkedList<>();
    private final int capacity;

    public WorkflowQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity less than 1: " + capacity);
        }
        this.capacity = capacity;
    }

    @Override
    public E take() throws InterruptedException {
        WorkflowThreadImpl.yield("WorkflowQueue.take", () -> !queue.isEmpty());
        return queue.remove();
    }

    @Override
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        boolean timedOut = WorkflowThreadImpl.yield(unit.toMillis(timeout), "WorkflowQueue.poll", () -> !queue.isEmpty());
        if (timedOut) {
            return null;
        }
        return queue.remove();
    }

    @Override
    public boolean offer(E e) {
        if (queue.size() == capacity) {
            return false;
        }
        queue.add(e);
        return true;
    }

    @Override
    public void put(E e) throws InterruptedException {
        WorkflowThreadImpl.yield("WorkflowQueue.put", () -> queue.size() < capacity);
        queue.add(e);
    }

    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        boolean timedOut = WorkflowThreadImpl.yield(unit.toMillis(timeout), "WorkflowQueue.offer", () -> queue.size() < capacity);
        if (timedOut) {
            return false;
        }
        queue.add(e);
        return true;
    }
}
