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

import com.uber.cadence.WorkflowService;

import java.lang.Thread.UncaughtExceptionHandler;

public interface WorkerBase extends SuspendableWorker {

    WorkflowService.Iface getService();

    String getDomain();

    /**
     * Task list name that given worker polls for tasks.
     */
    String getTaskListToPoll();

    double getMaximumPollRatePerSecond();

    /**
     * Maximum number of poll request to the task list per second allowed.
     * Default is 0 which means unlimited.
     * 
     * @see #setMaximumPollRateIntervalMilliseconds(int)
     */
    void setMaximumPollRatePerSecond(double maximumPollRatePerSecond);

    int getMaximumPollRateIntervalMilliseconds();

    /**
     * The sliding window interval used to measure the poll rate. Controls
     * allowed rate burstiness. For example if allowed rate is 10 per second and
     * poll rate interval is 100 milliseconds the poller is not going to allow
     * more then one poll per 100 milliseconds. If poll rate interval is 10
     * seconds then 100 request can be emitted as fast as possible, but then the
     * polling stops until 10 seconds pass.
     * 
     * @see #setMaximumPollRatePerSecond(double)
     */
    void setMaximumPollRateIntervalMilliseconds(int maximumPollRateIntervalMilliseconds);

    UncaughtExceptionHandler getUncaughtExceptionHandler();

    /**
     * Handler notified about poll request and other unexpected failures. The
     * default implementation logs the failures using ERROR level.
     */
    void setUncaughtExceptionHandler(UncaughtExceptionHandler uncaughtExceptionHandler);

    String getIdentity();

    /**
     * Set the identity that worker specifies in the poll requests. This value
     * ends up stored in the identity field of the corresponding Start history
     * event. Default is "pid"@"host".
     * 
     * @param identity
     *            maximum size is 256 characters.
     */
    void setIdentity(String identity);

    long getPollBackoffInitialInterval();

    /**
     * Failed poll requests are retried after an interval defined by an
     * exponential backoff algorithm. See BackoffThrottler for more info.
     * 
     * @param backoffInitialInterval
     *            the interval between failure and the first retry. Default is
     *            100.
     */
    void setPollBackoffInitialInterval(long backoffInitialInterval);

    long getPollBackoffMaximumInterval();

    /**
     * @see WorkerBase#setPollBackoffInitialInterval(long)
     * 
     * @param backoffMaximumInterval
     *            maximum interval between poll request retries. Default is
     *            60000 (one minute).
     */
    void setPollBackoffMaximumInterval(long backoffMaximumInterval);

    double getPollBackoffCoefficient();

    /**
     * @see WorkerBase#setPollBackoffInitialInterval(long)
     * 
     * @param backoffCoefficient
     *            coefficient that defines how fast retry interval grows in case
     *            of poll request failures. Default is 2.0.
     */
    void setPollBackoffCoefficient(double backoffCoefficient);

    int getPollThreadCount();

    /**
     * Defines how many concurrent threads are used by the given worker to poll
     * the specified task list. Default is 1. Note that in case of
     * {@link com.uber.cadence.internal.worker.GenericActivityWorker}
     * two separate threads pools are used. One for
     * polling and another one for executing activities. The size of the
     * activity execution thread pool is defined through
     * {@link com.uber.cadence.internal.worker.GenericActivityWorker#setTaskExecutorThreadPoolSize(int)}.
     */
    void setPollThreadCount(int threadCount);


    boolean isRunning();
}
