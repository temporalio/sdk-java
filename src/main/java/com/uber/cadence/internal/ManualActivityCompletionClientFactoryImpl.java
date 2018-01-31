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

import com.uber.cadence.WorkflowService;

public class ManualActivityCompletionClientFactoryImpl extends ManualActivityCompletionClientFactory {

    private WorkflowService.Iface service;

    private DataConverter dataConverter = new JsonDataConverter();
    
    public ManualActivityCompletionClientFactoryImpl(WorkflowService.Iface service) {
        this.service = service;
    }
    
    public WorkflowService.Iface getService() {
        return service;
    }
    
    public void setService(WorkflowService.Iface service) {
        this.service = service;
    }
    
    public DataConverter getDataConverter() {
        return dataConverter;
    }
    
    public void setDataConverter(DataConverter dataConverter) {
        this.dataConverter = dataConverter;
    }

    @Override
    public ManualActivityCompletionClient getClient(byte[] taskToken) {
        if (service == null) {
            throw new IllegalStateException("required property service is null");
        }
        if (dataConverter == null) {
            throw new IllegalStateException("required property dataConverter is null");
        }
        return new ManualActivityCompletionClientImpl(service, taskToken, dataConverter);
    }

}
