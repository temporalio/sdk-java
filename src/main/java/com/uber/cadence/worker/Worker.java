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
package com.uber.cadence.worker;

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowService;
import com.uber.cadence.internal.dispatcher.SyncWorkflowWorker;
import com.uber.cadence.internal.worker.ActivityWorker;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class Worker {

    private final WorkerOptions options;
    private final SyncWorkflowWorker workflowWorker;
    private final ActivityWorker activityWorker;

    public Worker(WorkflowService.Iface service, String domain, String taskList, WorkerOptions options) {
        if (options == null) {
            options = new WorkerOptions();
        }
        this.options = options;
        if (!options.isDisableActivityWorker()) {
            activityWorker = new ActivityWorker(service, domain, taskList);
            activityWorker.setMaximumPollRatePerSecond(options.getWorkerActivitiesPerSecond());
            if (options.getIdentity() != null) {
                activityWorker.setIdentity(options.getIdentity());
            }
            if (options.getDataConverter() != null) {
                activityWorker.setDataConverter(options.getDataConverter());
            }
            if (options.getMaxConcurrentActivityExecutionSize() > 0) {
                activityWorker.setTaskExecutorThreadPoolSize(options.getMaxConcurrentActivityExecutionSize());
            }
        } else {
            activityWorker = null;
        }
        if (!options.isDisableWorkflowWorker()) {
            workflowWorker = new SyncWorkflowWorker(service, domain, taskList);
            if (options.getIdentity() != null) {
                workflowWorker.setIdentity(options.getIdentity());
            }
            if (options.getDataConverter() != null) {
                workflowWorker.setDataConverter(options.getDataConverter());
            }
            if (options.getMaxWorkflowThreads() > 0) {
                workflowWorker.setWorkflowThreadPool(new ThreadPoolExecutor(1, options.getMaxWorkflowThreads(),
                        10, TimeUnit.SECONDS, new SynchronousQueue<>()));
            }
        } else {
            workflowWorker = null;
        }
    }

    public void addWorkflowImplementationType(Class<?> workflowImplementationClass) {
        if (workflowWorker == null) {
            throw new IllegalStateException("disableWorkflowWorker is set in worker options");
        }
        workflowWorker.addWorkflowImplementationType(workflowImplementationClass);
    }

    public void setWorkflowImplementationTypes(Class<?>... workflowImplementationType) {
        workflowWorker.setWorkflowImplementationTypes(workflowImplementationType);
    }

    public void addActivitiesImplementation(Object activityImplementation) {
        if (activityWorker == null) {
            throw new IllegalStateException("disableActivityWorker is set in worker options");
        }
        activityWorker.addActivityImplementation(activityImplementation);
    }

    public void setActivitiesImplementation(Object... activitiesImplementation) {
        activityWorker.setActivitiesImplementation(activitiesImplementation);
    }

    public void setIdentity(String identity) {
        if (workflowWorker != null) {
            workflowWorker.setIdentity(identity);
        }
        if (activityWorker != null) {
            activityWorker.setIdentity(identity);
        }
    }

    public void start() {
        if (workflowWorker != null) {
            workflowWorker.start();
        }
        if (activityWorker != null) {
            activityWorker.start();
        }
    }

    public void shutdown(long timeout, TimeUnit unit) {
        try {
            long time = System.currentTimeMillis();
            if (activityWorker != null) {
                activityWorker.shutdownAndAwaitTermination(timeout, unit);
            }
            if (workflowWorker != null) {
                long left = unit.toMillis(timeout) - (System.currentTimeMillis() - time);
                workflowWorker.shutdownAndAwaitTermination(left, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString() {
        return "Worker{" +
                "options=" + options +
                '}';
    }

    /**
     * This is an utility method to query a workflow execution using this particular instance of a worker.
     * It gets a history from a Cadence service, replays a workflow code and then runs the query.
     * This method is useful to troubleshoot workflows by running them in a debugger.
     * To work the workflow implementation type must be registered with this worker.
     * In most cases using {@link com.uber.cadence.client.CadenceClient} to query workflows is preferable,
     * as it doesn't require workflow implementation code to be available.
     *
     * @param execution  workflow execution to replay and then query locally
     * @param queryType  query type to execute
     * @param returnType return type of the query result
     * @param args       query arguments
     * @param <R>        type of the query result
     * @return query result
     */
    public <R> R queryWorkflowExecution(WorkflowExecution execution, String queryType, Class<R> returnType, Object... args) throws Exception {
        return workflowWorker.queryWorkflowExecution(execution, queryType, returnType, args);
    }
}
