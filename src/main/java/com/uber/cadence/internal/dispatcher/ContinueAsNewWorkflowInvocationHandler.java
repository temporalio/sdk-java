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

import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.workflow.ContinueAsNewWorkflowExecutionParameters;
import com.uber.cadence.workflow.QueryMethod;
import com.uber.cadence.workflow.SignalMethod;
import com.uber.cadence.workflow.WorkflowMethod;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

class ContinueAsNewWorkflowInvocationHandler implements InvocationHandler {

    private final ContinueAsNewWorkflowExecutionParameters parameters;
    private final SyncDecisionContext decisionContext;
    private final DataConverter dataConverter;

    public ContinueAsNewWorkflowInvocationHandler(ContinueAsNewWorkflowExecutionParameters parameters, SyncDecisionContext decisionContext) {
        this.parameters = parameters == null ? new ContinueAsNewWorkflowExecutionParameters() : parameters;
        this.decisionContext = decisionContext;
        this.dataConverter = decisionContext.getDataConverter();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        WorkflowMethod workflowMethod = method.getAnnotation(WorkflowMethod.class);
        QueryMethod queryMethod = method.getAnnotation(QueryMethod.class);
        SignalMethod signalMethod = method.getAnnotation(SignalMethod.class);
        int count = (workflowMethod == null ? 0 : 1) + (queryMethod == null ? 0 : 1) + (signalMethod == null ? 0 : 1);
        if (count > 1) {
            throw new IllegalArgumentException(method + " must contain at most one annotation " +
                    "from @WorkflowMethod, @QueryMethod or @SignalMethod");
        }
        if (workflowMethod == null) {
                throw new IllegalStateException("ContinueAsNew Stub supports only calls to methods annotated with @WorkflowMethod");
        }
        parameters.setInput(dataConverter.toData(args));
        decisionContext.continueAsNewOnCompletion(parameters);
        WorkflowThread.exit(null);
        return null;
    }
}
