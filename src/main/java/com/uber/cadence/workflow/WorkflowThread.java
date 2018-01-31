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
package com.uber.cadence.workflow;

import com.uber.cadence.internal.dispatcher.WorkflowInternal;

public interface WorkflowThread {

    void start();

    void join() throws InterruptedException;

    void join(long millis) throws InterruptedException;

    void interrupt();

    boolean isInterrupted();

    boolean isAlive();

    void setName(String name);

    String getName();

    long getId();

    Thread.State getState();

    static WorkflowThread currentThread() {
        return WorkflowInternal.currentThread();
    }

    static void sleep(long millis) throws InterruptedException {
        WorkflowInternal.yield(millis, "sleep", () -> false   );
    }

    static boolean interrupted() {
        return WorkflowInternal.currentThreadResetInterrupted();
    }

    String getStackTrace();
}