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

import com.google.common.base.Throwables;
import com.google.common.reflect.TypeToken;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.internal.DataConverter;
import com.uber.cadence.internal.WorkflowException;
import com.uber.cadence.internal.common.FlowHelpers;
import com.uber.cadence.internal.dispatcher.SyncWorkflowDefinition;
import com.uber.cadence.internal.dispatcher.WorkflowInternal;
import com.uber.cadence.workflow.Functions;
import com.uber.cadence.workflow.QueryMethod;
import com.uber.cadence.workflow.SignalMethod;
import com.uber.cadence.workflow.WorkflowMethod;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

public class POJOWorkflowImplementationFactory implements Function<WorkflowType, SyncWorkflowDefinition> {

    private static final Log log = LogFactory.getLog(GenericWorker.class);
    private static final byte[] EMPTY_BLOB = {};

    private DataConverter dataConverter;

    /**
     * Key: workflow type name, Value: function that creates SyncWorkflowDefinition instance.
     */
    private final Map<String, Functions.Func<SyncWorkflowDefinition>> factories = Collections.synchronizedMap(new HashMap<>());

    public POJOWorkflowImplementationFactory(DataConverter dataConverter) {
        this.dataConverter = dataConverter;
    }

    public void setWorkflowImplementationTypes(Class<?>[] workflowImplementationTypes) {
        factories.clear();
        for (Class<?> type : workflowImplementationTypes) {
            addWorkflowImplementationType(type);
        }
    }

    public void addWorkflowImplementationType(Class<?> workflowImplementationClass) {
        TypeToken<?>.TypeSet interfaces = TypeToken.of(workflowImplementationClass).getTypes().interfaces();
        if (interfaces.isEmpty()) {
            throw new IllegalArgumentException("Workflow must implement at least one interface");
        }
        boolean hasWorkflowMethod = false;
        for (TypeToken<?> i : interfaces) {
            Map<String, Method> signalHandlers = new HashMap<>();
            for (Method method : i.getRawType().getMethods()) {
                WorkflowMethod workflowMethod = method.getAnnotation(WorkflowMethod.class);
                QueryMethod queryMethod = method.getAnnotation(QueryMethod.class);
                SignalMethod signalMethod = method.getAnnotation(SignalMethod.class);
                int count = (workflowMethod == null ? 0 : 1) + (queryMethod == null ? 0 : 1) + (signalMethod == null ? 0 : 1);
                if (count > 1) {
                    throw new IllegalArgumentException(method + " must contain at most one annotation " +
                            "from @WorkflowMethod, @QueryMethod or @SignalMethod");
                }
                if (workflowMethod != null) {
                    Functions.Func<SyncWorkflowDefinition> factory =
                            () -> new POJOWorkflowImplementation(method, workflowImplementationClass, signalHandlers);

                    String workflowName = workflowMethod.name();
                    if (workflowName.isEmpty()) {
                        workflowName = FlowHelpers.getSimpleName(method);
                    }
                    factories.put(workflowName, factory);
                    hasWorkflowMethod = true;
                }
                if (signalMethod != null) {
                    if (method.getReturnType() != Void.TYPE) {
                        throw new IllegalArgumentException("Method annotated with @SignalMethod " +
                                "must have void return type: " + method);
                    }
                    String signalName = signalMethod.name();
                    if (signalName.isEmpty()) {
                        signalName = FlowHelpers.getSimpleName(method);
                    }
                    signalHandlers.put(signalName, method);
                }
                if (queryMethod != null) {
                    if (method.getReturnType() == Void.TYPE) {
                        throw new IllegalArgumentException("Method annotated with @QueryMethod " +
                                "cannot have void return type: " + method);
                    }
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
        Functions.Func<SyncWorkflowDefinition> factory = factories.get(workflowType.getName());
        if (factory == null) {
            return null;
        }
        try {
            return factory.apply();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void setDataConverter(DataConverter dataConverter) {
        this.dataConverter = dataConverter;
    }

    private class POJOWorkflowImplementation implements SyncWorkflowDefinition {

        private final Method method;
        private final Class<?> workflowImplementationClass;
        private final Map<String, Method> signalHandlers;
        private Object workflow;

        public POJOWorkflowImplementation(Method method, Class<?> workflowImplementationClass, Map<String, Method> signalHandlers) {
            this.method = method;
            this.workflowImplementationClass = workflowImplementationClass;
            this.signalHandlers = signalHandlers;
        }

        @Override
        public byte[] execute(byte[] input) throws CancellationException, WorkflowException {
            Object[] args = dataConverter.fromData(input, Object[].class);
            try {
                newInstance();
                Object result = method.invoke(workflow, args);
                if (method.getReturnType() == Void.TYPE) {
                    return EMPTY_BLOB;
                }
                return dataConverter.toData(result);
            } catch (IllegalAccessException e) {
                throw throwWorkflowFailure(e);
            } catch (InvocationTargetException e) {
                Throwable targetException = e.getTargetException();
                if (targetException instanceof Error) {
                    throw (Error) targetException;
                }
                if (targetException instanceof CancellationException) {
                    throw (CancellationException) targetException;
                }
                throw throwWorkflowFailure(targetException);
            }
        }

        private void newInstance() throws IllegalAccessException {
            if (workflow == null) {
                try {
                    workflow = workflowImplementationClass.newInstance();
                } catch (InstantiationException e) {
                    throw new RuntimeException(e);
                }
                WorkflowInternal.registerQuery(workflow);
            }
        }

        @Override
        public void processSignal(String signalName, byte[] input) {
            Object[] args = dataConverter.fromData(input, Object[].class);
            Method method = signalHandlers.get(signalName);
            if (method == null) {
                log.warn("Unknown signal: " + signalName + ", knownSignals=" + signalHandlers.keySet());
                throw new IllegalArgumentException("Unknown signal: " + signalName);
            }
            try {
                newInstance();
                method.invoke(workflow, args);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            } catch (InvocationTargetException e) {
                Throwable targetException = e.getTargetException();
                if (targetException instanceof Error) {
                    throw (Error) targetException;
                }
                throw new RuntimeException(e.getTargetException());
            }
        }

        private WorkflowException throwWorkflowFailure(Throwable e) {
            if (e instanceof CancellationException) {
                throw (CancellationException) e;
            }
            if (e instanceof WorkflowException) {
                return (WorkflowException) e;
            }
            return new WorkflowException(e.getMessage(),
                    Throwables.getStackTraceAsString(e).getBytes(StandardCharsets.UTF_8));
        }
    }
}
