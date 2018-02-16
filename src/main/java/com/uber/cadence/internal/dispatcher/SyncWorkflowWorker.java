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

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowService;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.internal.worker.AsyncDecisionTaskHandler;
import com.uber.cadence.internal.worker.AsyncWorkflowFactory;
import com.uber.cadence.internal.worker.DecisionTaskPoller;
import com.uber.cadence.internal.worker.GenericWorker;
import com.uber.cadence.internal.worker.POJOWorkflowImplementationFactory;
import com.uber.cadence.internal.worker.TaskPoller;

import java.lang.management.ManagementFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class SyncWorkflowWorker extends GenericWorker {

    private static final String THREAD_NAME_PREFIX = "Cadence workflow poller ";

    private final POJOWorkflowImplementationFactory factory;

    private ExecutorService workflowThreadPool;

    private DecisionTaskPoller decisionTaskPoller;

    private final DataConverter dataConverter;

    public SyncWorkflowWorker(WorkflowService.Iface service, String domain, String taskListToPoll, DataConverter dataConverter) {
        setIdentity(ManagementFactory.getRuntimeMXBean().getName());
        workflowThreadPool = new ThreadPoolExecutor(1, 1000,
                10, TimeUnit.SECONDS, new SynchronousQueue<>());
        setService(service);
        setDomain(domain);
        setTaskListToPoll(taskListToPoll);
        this.dataConverter = dataConverter;
        factory = new POJOWorkflowImplementationFactory(dataConverter);
    }

    public void addWorkflowImplementationType(Class<?> workflowImplementationClass) {
        factory.addWorkflowImplementationType(workflowImplementationClass);
    }

    public void setWorkflowImplementationTypes(Class<?>[] workflowImplementationTypes) {
        factory.setWorkflowImplementationTypes(workflowImplementationTypes);
    }

    public void setWorkflowThreadPool(ExecutorService workflowThreadPool) {
        this.workflowThreadPool = workflowThreadPool;
    }

    @Override
    protected void checkRequredProperties() {
        checkRequiredProperty(factory, "factory");
    }

    @Override
    protected boolean isNeeded() {
        return factory.getWorkflowImplementationTypeCount() > 0;
    }

    @Override
    protected String getPollThreadNamePrefix() {
        return THREAD_NAME_PREFIX + getTaskListToPoll() + " ";
    }

    public <R> R queryWorkflowExecution(WorkflowExecution execution, String queryType, Class<R> returnType, Object... args) throws Exception {
        createDecisionTaskPoller();
        byte[] serializedArgs = dataConverter.toData(args);
        byte[] result = decisionTaskPoller.queryWorkflowExecution(execution, queryType, serializedArgs);
        return dataConverter.fromData(result, returnType);
    }

    @Override
    protected TaskPoller createPoller() {
        createDecisionTaskPoller();
        return decisionTaskPoller;
    }

    private void createDecisionTaskPoller() {
        if (decisionTaskPoller != null) {
            return;
        }
        decisionTaskPoller = new DecisionTaskPoller();
        AsyncWorkflowFactory workflowFactory = new SyncWorkflowFactory(factory, dataConverter, workflowThreadPool);
        decisionTaskPoller.setDecisionTaskHandler(new AsyncDecisionTaskHandler(domain, workflowFactory));
        decisionTaskPoller.setDomain(getDomain());
        decisionTaskPoller.setIdentity(getIdentity());
        decisionTaskPoller.setService(getService());
        decisionTaskPoller.setTaskListToPoll(getTaskListToPoll());
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

    /**
     * @return true if terminated
     */
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long start = System.currentTimeMillis();
        boolean terminated = super.awaitTermination(timeout, unit);
        long elapsed = System.currentTimeMillis() - start;
        long left = TimeUnit.MILLISECONDS.convert(timeout, unit) - elapsed;
        return workflowThreadPool.awaitTermination(left, TimeUnit.MILLISECONDS) && terminated;
    }

    /**
     * @return true if terminated
     */
    @Override
    public boolean shutdownAndAwaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        shutdownNow();
        return awaitTermination(timeout, TimeUnit.MILLISECONDS);
    }
}
