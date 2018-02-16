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
package com.uber.cadence.internal;

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowService;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.internal.generic.GenericWorkflowClientExternal;
import com.uber.cadence.internal.worker.GenericWorkflowClientExternalImpl;

abstract class WorkflowClientFactoryExternalBase<T> implements WorkflowClientFactoryExternal<T> {

    private GenericWorkflowClientExternal genericClient;

    private DataConverter dataConverter;

    private WorkflowOptions startWorkflowOptions = new WorkflowOptions.Builder().build();

    public WorkflowClientFactoryExternalBase(WorkflowService.Iface service, String domain) {
        this(new GenericWorkflowClientExternalImpl(service, domain));
    }

    public WorkflowClientFactoryExternalBase(GenericWorkflowClientExternal genericClient) {
        this.genericClient = genericClient;
    }

    @Override
    public GenericWorkflowClientExternal getGenericClient() {
        return genericClient;
    }

    public void setGenericClient(GenericWorkflowClientExternal genericClient) {
        this.genericClient = genericClient;
    }

    @Override
    public DataConverter getDataConverter() {
        return dataConverter;
    }

    public void setDataConverter(DataConverter dataConverter) {
        this.dataConverter = dataConverter;
    }

    @Override
    public WorkflowOptions getStartWorkflowOptions() {
        return startWorkflowOptions;
    }

    public void setStartWorkflowOptions(WorkflowOptions startWorkflowOptions) {
        this.startWorkflowOptions = startWorkflowOptions;
    }

    @Override
    public T getClient() {
        checkConfigured();
        String workflowId = genericClient.generateUniqueId();
        WorkflowExecution workflowExecution = new WorkflowExecution().setWorkflowId(workflowId);
        return getClient(workflowExecution, startWorkflowOptions, dataConverter, genericClient);
    }

    @Override
    public T getClient(String workflowId) {
        if (workflowId == null || workflowId.isEmpty()) {
            throw new IllegalArgumentException("workflowId");
        }
        WorkflowExecution workflowExecution = new WorkflowExecution().setWorkflowId(workflowId);
        return getClient(workflowExecution, startWorkflowOptions, dataConverter, genericClient);
    }

    @Override
    public T getClient(WorkflowExecution workflowExecution) {
        return getClient(workflowExecution, startWorkflowOptions, dataConverter, genericClient);
    }

    @Override
    public T getClient(WorkflowExecution workflowExecution, WorkflowOptions options) {
        return getClient(workflowExecution, options, dataConverter, genericClient);
    }

    @Override
    public T getClient(WorkflowExecution workflowExecution, WorkflowOptions options, DataConverter dataConverter) {
        return getClient(workflowExecution, options, dataConverter, genericClient);
    }

    @Override
    public T getClient(WorkflowExecution workflowExecution, WorkflowOptions options, DataConverter dataConverter,
                       GenericWorkflowClientExternal genericClient) {
        checkConfigured();
        return createClientInstance(workflowExecution, options, dataConverter, genericClient);
    }

    private void checkConfigured() {
        if (dataConverter == null) {
            throw new IllegalStateException("required property dataConverter is not set");
        }
        if (genericClient == null) {
            throw new IllegalStateException("The required property genericClient is null. "
                    + "It could be caused by instantiating the factory through the default constructor instead of the one "
                    + "that takes service and domain arguments.");
        }
    }

    protected abstract T createClientInstance(WorkflowExecution workflowExecution, WorkflowOptions options,
            DataConverter dataConverter, GenericWorkflowClientExternal genericClient);

}
