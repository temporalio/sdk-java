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

package com.uber.cadence.internal.sync;

import com.uber.cadence.WorkflowService;
import com.uber.cadence.internal.worker.ActivityWorker;
import com.uber.cadence.internal.worker.SingleWorkerOptions;

import java.util.concurrent.TimeUnit;

/**
 * Activity worker that supports POJO activity implementations.
 */
public class SyncActivityWorker {

    private final ActivityWorker worker;
    private final POJOActivityTaskHandler taskHandler;

    public SyncActivityWorker(WorkflowService.Iface service, String domain, String taskList, SingleWorkerOptions options) {
        taskHandler = new POJOActivityTaskHandler(options.getDataConverter());
        worker = new ActivityWorker(service, domain, taskList, options, taskHandler);
    }

    public void setActivitiesImplementation(Object... activitiesImplementation) {
        taskHandler.setActivitiesImplementation(activitiesImplementation);
    }

    public void start() {
        worker.start();
    }

    public void shutdown() {
        worker.shutdown();
    }

    public void shutdownNow() {
        worker.shutdownNow();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return worker.awaitTermination(timeout, unit);
    }

    public boolean shutdownAndAwaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return worker.shutdownAndAwaitTermination(timeout, unit);
    }

    public boolean isRunning() {
        return worker.isRunning();
    }

    public void suspendPolling() {
        worker.suspendPolling();
    }

    public void resumePolling() {
        worker.resumePolling();
    }
}
