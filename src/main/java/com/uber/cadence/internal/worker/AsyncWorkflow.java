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

import com.uber.cadence.internal.AsyncDecisionContext;
import com.uber.cadence.internal.WorkflowException;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.WorkflowQuery;
import com.uber.cadence.workflow.WorkflowThread;

import java.util.concurrent.CancellationException;

public interface AsyncWorkflow {
    
    void start(HistoryEvent event, AsyncDecisionContext context) throws Exception;

    void processSignal(String signalName, byte[] input);

    boolean eventLoop() throws Throwable;

    /**
     *
     * @return null means no output yet
     */
    byte[] getOutput();

    void cancel(String reason);

    Throwable getFailure();

    boolean isCancelRequested();

    void close();

    /**
     * @return time at which workflow can make progress.
     * For example when {@link WorkflowThread#sleep(long)} expires.
     */
    long getNextWakeUpTime();

    /**
     * Called after all history is replayed and workflow cannot make any progress if decision task is a query.
     * @param query
     */
    byte[] query(WorkflowQuery query);
}
