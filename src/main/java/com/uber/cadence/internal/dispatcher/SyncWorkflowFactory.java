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
import com.uber.cadence.worker.AsyncWorkflow;
import com.uber.cadence.worker.AsyncWorkflowFactory;
import com.uber.cadence.WorkflowType;

import java.util.concurrent.ExecutorService;
import java.util.function.Function;

public class SyncWorkflowFactory implements AsyncWorkflowFactory {
    private final Function<WorkflowType, SyncWorkflowDefinition> factory;
    private final DataConverter converter;
    private final ExecutorService threadPool;

    public SyncWorkflowFactory(Function<WorkflowType, SyncWorkflowDefinition> factory, DataConverter converter, ExecutorService threadPool) {
        this.factory = factory;
        this.converter = converter;
        this.threadPool = threadPool;
    }

    @Override
    public AsyncWorkflow getWorkflow(WorkflowType workflowType) {
        return new SyncWorkflow(factory, converter, threadPool);
    }
}
