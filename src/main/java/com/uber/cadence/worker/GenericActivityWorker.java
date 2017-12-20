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

import com.uber.cadence.generic.ActivityImplementationFactory;
import com.uber.cadence.WorkflowService;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class GenericActivityWorker extends GenericWorker {

    private static final Log log = LogFactory.getLog(GenericActivityWorker.class);

    private static final String POLL_THREAD_NAME_PREFIX = "SWF Activity Poll ";

    private static final String ACTIVITY_THREAD_NAME_PREFIX = "SWF Activity ";

    private ActivityImplementationFactory activityImplementationFactory;

    private int taskExecutorThreadPoolSize = 100;

    public GenericActivityWorker(WorkflowService.Iface service, String domain, String taskListToPoll) {
        super(service, domain, taskListToPoll);
        if (service == null) {
            throw new IllegalArgumentException("service");
        }
    }

    public GenericActivityWorker() {
        super();
    }

    public ActivityImplementationFactory getActivityImplementationFactory() {
        return activityImplementationFactory;
    }

    public void setActivityImplementationFactory(ActivityImplementationFactory activityImplementationFactory) {
        this.activityImplementationFactory = activityImplementationFactory;
    }

    public int getTaskExecutorThreadPoolSize() {
        return taskExecutorThreadPoolSize;
    }

    public void setTaskExecutorThreadPoolSize(int taskExecutorThreadPoolSize) {
        if (taskExecutorThreadPoolSize < 1) {
            throw new IllegalArgumentException("0 or negative taskExecutorThreadPoolSize");
        }
        checkStarted();
        this.taskExecutorThreadPoolSize = taskExecutorThreadPoolSize;
    }

    protected Semaphore createPollSemaphore() {
        return new Semaphore(taskExecutorThreadPoolSize);
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + " [super=" + super.toString() + ", taskExecutorThreadPoolSize="
                + taskExecutorThreadPoolSize + "]";
    }

    @Override
    protected String getPollThreadNamePrefix() {
        return POLL_THREAD_NAME_PREFIX + getTaskListToPoll() + " ";
    }

    @Override
    protected TaskPoller createPoller() {
        ThreadPoolExecutor tasksExecutor = new ThreadPoolExecutor(1, taskExecutorThreadPoolSize, 1, TimeUnit.MINUTES,
                new SynchronousQueue<Runnable>());
        tasksExecutor.setThreadFactory(new ExecutorThreadFactory(ACTIVITY_THREAD_NAME_PREFIX + " " + getTaskListToPoll() + " "));
        tasksExecutor.setRejectedExecutionHandler(new BlockCallerPolicy());
        return new ActivityTaskPoller(service, domain, getTaskListToPoll(), activityImplementationFactory, tasksExecutor);
    }

    @Override
    protected void checkRequredProperties() {
        checkRequiredProperty(activityImplementationFactory, "activityImplementationFactory");
    }

}
