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

import com.uber.cadence.DataConverter;
import com.uber.cadence.JsonDataConverter;
import com.uber.cadence.worker.AsyncDecisionTaskHandler;
import com.uber.cadence.worker.AsyncWorkflowFactory;
import com.uber.cadence.worker.DecisionTaskPoller;
import com.uber.cadence.worker.GenericWorker;
import com.uber.cadence.worker.TaskPoller;
import com.uber.cadence.WorkflowService;
import com.uber.cadence.WorkflowType;

import java.lang.management.ManagementFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;


public class SyncWorkflowWorker extends GenericWorker {

    private static final String THREAD_NAME_PREFIX = "Cadence workflow poller ";

    private Function<WorkflowType, SyncWorkflowDefinition> factory;

    private DataConverter dataConverter = new JsonDataConverter();

    private ExecutorService workflowThreadPool;

    public SyncWorkflowWorker() {
        setIdentity(ManagementFactory.getRuntimeMXBean().getName());
        workflowThreadPool = new ThreadPoolExecutor(1, 1000,
                10, TimeUnit.SECONDS, new SynchronousQueue<>());
    }

    public SyncWorkflowWorker(WorkflowService.Iface service, String domain, String taskListToPoll) {
        this();
        setService(service);
        setDomain(domain);
        setTaskListToPoll(taskListToPoll);
    }

    public void setFactory(Function<WorkflowType, SyncWorkflowDefinition> factory) {
        this.factory = factory;
    }

    public void setDataConverter(DataConverter dataConverter) {
        this.dataConverter = dataConverter;
    }

    public void setWorkflowThreadPool(ExecutorService workflowThreadPool) {
        this.workflowThreadPool = workflowThreadPool;
    }

    @Override
    protected void checkRequredProperties() {
        checkRequiredProperty(factory, "factory");
    }

    @Override
    protected String getPollThreadNamePrefix() {
        return THREAD_NAME_PREFIX + getTaskListToPoll() + " ";
    }

    @Override
    protected TaskPoller createPoller() {
        DecisionTaskPoller result = new DecisionTaskPoller();
        AsyncWorkflowFactory workflowFactory = new SyncWorkflowFactory(factory, dataConverter, workflowThreadPool);
        result.setDecisionTaskHandler(new AsyncDecisionTaskHandler(workflowFactory));
        result.setDomain(getDomain());
        result.setIdentity(getIdentity());
        result.setService(getService());
        result.setTaskListToPoll(getTaskListToPoll());
        return result;
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "[super=" + super.toString() + ", workflowDefinitionFactoryFactory="
                + factory + "]";
    }

    @Override
    public void shutdown() {
        super.shutdown();
        workflowThreadPool.shutdown();
    }

    @Override
    public void shutdownNow() {
        super.shutdownNow();
        workflowThreadPool.shutdownNow();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long start = System.currentTimeMillis();
        boolean terminated = super.awaitTermination(timeout, unit);
        long elapsed = System.currentTimeMillis() - start;
        long left = TimeUnit.MILLISECONDS.convert(timeout, unit) - elapsed;
        return workflowThreadPool.awaitTermination(left, TimeUnit.MILLISECONDS) && terminated;
    }

    @Override
    public boolean shutdownAndAwaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        shutdownNow();
        return awaitTermination(timeout, TimeUnit.MILLISECONDS);
    }

}
