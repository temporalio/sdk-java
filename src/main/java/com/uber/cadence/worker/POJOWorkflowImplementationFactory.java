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

import com.google.common.base.Throwables;
import com.google.common.reflect.TypeToken;
import com.uber.cadence.DataConverter;
import com.uber.cadence.WorkflowException;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.common.FlowHelpers;
import com.uber.cadence.internal.dispatcher.SyncWorkflowDefinition;
import com.uber.cadence.internal.dispatcher.Workflow;
import com.uber.cadence.internal.dispatcher.WorkflowMethod;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

public class POJOWorkflowImplementationFactory implements Function<WorkflowType, SyncWorkflowDefinition> {

    private static final byte[] EMPTY_BLOB = {};
    private DataConverter dataConverter;
    private final Map<String, SyncWorkflowDefinition> workflows = Collections.synchronizedMap(new HashMap<>());

    public POJOWorkflowImplementationFactory(DataConverter dataConverter) {
        this.dataConverter = dataConverter;
    }

    public void addWorkflow(Class<?> workflowImplementationClass) {
        TypeToken<?>.TypeSet interfaces = TypeToken.of(workflowImplementationClass).getTypes().interfaces();
        if (interfaces.isEmpty()) {
            throw new IllegalArgumentException("Workflow must implement at least one interface");
        }
        boolean hasWorkflowMethod = false;
        for (TypeToken<?> i : interfaces) {
            for (Method method : i.getRawType().getMethods()) {
                WorkflowMethod workflowMethod = method.getAnnotation(WorkflowMethod.class);
                if (workflowMethod != null) {
                    SyncWorkflowDefinition implementation = new POJOWorkflowImplementation(method, workflowImplementationClass);
                    String workflowName = workflowMethod.name();
                    if (workflowName.isEmpty()) {
                        workflowName = FlowHelpers.getSimpleName(method);
                    }
                    workflows.put(workflowName, implementation);
                    hasWorkflowMethod = true;
                }
            }
            // TODO: Query methods.
        }
        if (!hasWorkflowMethod) {
            throw new IllegalArgumentException("Workflow implementation doesn't implement interface " +
                    "with method annotated with @WorkflowMethod: " + workflowImplementationClass);
        }
    }

    @Override
    public SyncWorkflowDefinition apply(WorkflowType workflowType) {
        return workflows.get(workflowType.getName());
    }

    public void setDataConverter(DataConverter dataConverter) {
        this.dataConverter = dataConverter;
    }

    private class POJOWorkflowImplementation implements SyncWorkflowDefinition {

        private final Method method;
        private final Class<?> workflowImplementationClass;

        public POJOWorkflowImplementation(Method method, Class<?> workflowImplementationClass) {
            this.method = method;
            this.workflowImplementationClass = workflowImplementationClass;
        }

        @Override
        public byte[] execute(byte[] input) throws CancellationException, WorkflowException {
            Object[] args = dataConverter.fromData(input, Object[].class);
            try {
                Object workflow = workflowImplementationClass.newInstance();
                Workflow.registerQuery(workflow);
                Object result = method.invoke(workflow, args);
                if (method.getReturnType() == Void.TYPE) {
                    return EMPTY_BLOB;
                }
                return dataConverter.toData(result);
            } catch (IllegalAccessException e) {
                throw throwWorkflowFailure(e);
            } catch (InvocationTargetException e) {
                throw throwWorkflowFailure(e.getTargetException());
            } catch (InstantiationException e) {
                throw new RuntimeException(e);
            }
        }

        private WorkflowException throwWorkflowFailure(Throwable e) {
            if (e instanceof WorkflowException) {
                return (WorkflowException) e;
            }
            return new WorkflowException(e.getMessage(),
                    Throwables.getStackTraceAsString(e).getBytes(StandardCharsets.UTF_8));
        }
    }
}
