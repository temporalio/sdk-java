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
package com.uber.cadence.internal.worker;

import com.uber.cadence.internal.DataConverter;
import com.uber.cadence.internal.JsonDataConverter;
import com.uber.cadence.WorkflowService;

import java.util.concurrent.TimeUnit;

public class ActivityWorker {

    private final static DataConverter DEFAULT_DATA_CONVERTER = new JsonDataConverter();

    private final GenericActivityWorker worker;
    private final POJOActivityImplementationFactory factory =
            new POJOActivityImplementationFactory(DEFAULT_DATA_CONVERTER);

    public ActivityWorker(WorkflowService.Iface service, String domain, String taskList) {
        worker = new GenericActivityWorker(service, domain, taskList);
        worker.setActivityImplementationFactory(factory);
    }

    public void addActivityImplementation(Object activity) {
        factory.addActivityImplementation(activity);
    }

    public WorkflowService.Iface getService() {
        return worker.getService();
    }

    public void setService(WorkflowService.Iface service) {
        worker.setService(service);
    }

    public String getDomain() {
        return worker.getDomain();
    }

    public void setDomain(String domain) {
        worker.setDomain(domain);
    }

    public boolean isRegisterDomain() {
        return worker.isRegisterDomain();
    }

    public void setRegisterDomain(boolean registerDomain) {
        worker.setRegisterDomain(registerDomain);
    }

    public int getDomainRetentionPeriodInDays() {
        return worker.getDomainRetentionPeriodInDays();
    }

    public void setDomainRetentionPeriodInDays(int domainRetentionPeriodInDays) {
        worker.setDomainRetentionPeriodInDays(domainRetentionPeriodInDays);
    }

    public String getTaskListToPoll() {
        return worker.getTaskListToPoll();
    }

    public void setTaskListToPoll(String taskListToPoll) {
        worker.setTaskListToPoll(taskListToPoll);
    }

    public double getMaximumPollRatePerSecond() {
        return worker.getMaximumPollRatePerSecond();
    }

    public void setMaximumPollRatePerSecond(double maximumPollRatePerSecond) {
        worker.setMaximumPollRatePerSecond(maximumPollRatePerSecond);
    }

    public int getMaximumPollRateIntervalMilliseconds() {
        return worker.getMaximumPollRateIntervalMilliseconds();
    }

    public void setMaximumPollRateIntervalMilliseconds(int maximumPollRateIntervalMilliseconds) {
        worker.setMaximumPollRateIntervalMilliseconds(maximumPollRateIntervalMilliseconds);
    }

    public Thread.UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return worker.getUncaughtExceptionHandler();
    }

    public void setUncaughtExceptionHandler(Thread.UncaughtExceptionHandler uncaughtExceptionHandler) {
        worker.setUncaughtExceptionHandler(uncaughtExceptionHandler);
    }

    public String getIdentity() {
        return worker.getIdentity();
    }

    public void setIdentity(String identity) {
        worker.setIdentity(identity);
    }

    public long getPollBackoffInitialInterval() {
        return worker.getPollBackoffInitialInterval();
    }

    public void setPollBackoffInitialInterval(long backoffInitialInterval) {
        worker.setPollBackoffInitialInterval(backoffInitialInterval);
    }

    public long getPollBackoffMaximumInterval() {
        return worker.getPollBackoffMaximumInterval();
    }

    public void setPollBackoffMaximumInterval(long backoffMaximumInterval) {
        worker.setPollBackoffMaximumInterval(backoffMaximumInterval);
    }

    public double getPollBackoffCoefficient() {
        return worker.getPollBackoffCoefficient();
    }

    public void setPollBackoffCoefficient(double backoffCoefficient) {
        worker.setPollBackoffCoefficient(backoffCoefficient);
    }

    public int getPollThreadCount() {
        return worker.getPollThreadCount();
    }

    public void setPollThreadCount(int threadCount) {
        worker.setPollThreadCount(threadCount);
    }

    public void start() {
        worker.start();
    }

    public void checkRequiredProperty(Object value, String name) {
        worker.checkRequiredProperty(value, name);
    }

    public void checkStarted() {
        worker.checkStarted();
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
