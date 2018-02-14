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

public class ManualActivityCompletionClientFactoryImpl extends ManualActivityCompletionClientFactory {

    private final WorkflowService.Iface service;

    private final DataConverter dataConverter;
    private final String domain;

    public ManualActivityCompletionClientFactoryImpl(WorkflowService.Iface service, String domain, DataConverter dataConverter) {
        this.service = service;
        this.domain = domain;
        this.dataConverter = dataConverter;
    }

    public WorkflowService.Iface getService() {
        return service;
    }

    public DataConverter getDataConverter() {
        return dataConverter;
    }

    @Override
    public ManualActivityCompletionClient getClient(byte[] taskToken) {
        if (service == null) {
            throw new IllegalStateException("required property service is null");
        }
        if (dataConverter == null) {
            throw new IllegalStateException("required property dataConverter is null");
        }
        if (taskToken == null || taskToken.length == 0) {
            throw new IllegalArgumentException("null or empty task token");
        }
        return new ManualActivityCompletionClientImpl(service, taskToken, dataConverter);
    }

    @Override
    public ManualActivityCompletionClient getClient(WorkflowExecution execution, String activityId) {
        if (execution == null) {
            throw new IllegalArgumentException("null execution");
        }
        if (activityId == null) {
            throw new IllegalArgumentException("null activityId");
        }
        return new ManualActivityCompletionClientImpl(service, domain, execution, activityId, dataConverter);
    }

}
