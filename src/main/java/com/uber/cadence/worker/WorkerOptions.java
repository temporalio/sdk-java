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

import com.uber.cadence.converter.DataConverter;

public final class WorkerOptions {

    boolean disableWorkflowWorker;
    boolean disableActivityWorker;
    double workerActivitiesPerSecond;
    String identity;
    DataConverter dataConverter;
    int maxConcurrentActivityExecutionSize;
    int maxWorkflowThreads;

    public int getMaxConcurrentActivityExecutionSize() {
        return maxConcurrentActivityExecutionSize;
    }

    /**
     * Maximum number of parallely executed activities.
     */
    public void setMaxConcurrentActivityExecutionSize(int maxConcurrentActivityExecutionSize) {
        this.maxConcurrentActivityExecutionSize = maxConcurrentActivityExecutionSize;
    }

    public int getMaxWorkflowThreads() {
        return maxWorkflowThreads;
    }

    /**
     * Maximum size of a thread pool used to execute workflows.
     */
    public void setMaxWorkflowThreads(int maxWorkflowThreads) {
        this.maxWorkflowThreads = maxWorkflowThreads;
    }

    public String getIdentity() {
        return identity;
    }

    public void setIdentity(String identity) {
        this.identity = identity;
    }

    public DataConverter getDataConverter() {
        return dataConverter;
    }

    public void setDataConverter(DataConverter dataConverter) {
        this.dataConverter = dataConverter;
    }

    public boolean isDisableWorkflowWorker() {
        return disableWorkflowWorker;
    }

    public void setDisableWorkflowWorker(boolean disableWorkflowWorker) {
        this.disableWorkflowWorker = disableWorkflowWorker;
    }

    public boolean isDisableActivityWorker() {
        return disableActivityWorker;
    }

    public void setDisableActivityWorker(boolean disableActivityWorker) {
        this.disableActivityWorker = disableActivityWorker;
    }

    public double getWorkerActivitiesPerSecond() {
        return workerActivitiesPerSecond;
    }

    /**
     * Maximum number of activities started per second.
     * Default is 0 which means unlimited.
     */
    public void setWorkerActivitiesPerSecond(double workerActivitiesPerSecond) {
        this.workerActivitiesPerSecond = workerActivitiesPerSecond;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        WorkerOptions that = (WorkerOptions) o;

        if (disableWorkflowWorker != that.disableWorkflowWorker) return false;
        if (disableActivityWorker != that.disableActivityWorker) return false;
        if (Double.compare(that.workerActivitiesPerSecond, workerActivitiesPerSecond) != 0) return false;
        if (maxConcurrentActivityExecutionSize != that.maxConcurrentActivityExecutionSize) return false;
        if (maxWorkflowThreads != that.maxWorkflowThreads) return false;
        if (identity != null ? !identity.equals(that.identity) : that.identity != null) return false;
        return dataConverter != null ? dataConverter.equals(that.dataConverter) : that.dataConverter == null;
    }

    @Override
    public int hashCode() {
        int result;
        long temp;
        result = (disableWorkflowWorker ? 1 : 0);
        result = 31 * result + (disableActivityWorker ? 1 : 0);
        temp = Double.doubleToLongBits(workerActivitiesPerSecond);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        result = 31 * result + (identity != null ? identity.hashCode() : 0);
        result = 31 * result + (dataConverter != null ? dataConverter.hashCode() : 0);
        result = 31 * result + maxConcurrentActivityExecutionSize;
        result = 31 * result + maxWorkflowThreads;
        return result;
    }

    @Override
    public String toString() {
        return "WorkerOptions{" +
                "disableWorkflowWorker=" + disableWorkflowWorker +
                ", disableActivityWorker=" + disableActivityWorker +
                ", workerActivitiesPerSecond=" + workerActivitiesPerSecond +
                ", identity='" + identity + '\'' +
                ", dataConverter=" + dataConverter +
                ", maxConcurrentActivityExecutionSize=" + maxConcurrentActivityExecutionSize +
                ", maxWorkflowThreads=" + maxWorkflowThreads +
                '}';
    }
}
