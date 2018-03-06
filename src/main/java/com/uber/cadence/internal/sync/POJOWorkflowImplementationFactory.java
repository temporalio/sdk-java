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
package com.uber.cadence.internal.sync;

import com.google.common.reflect.TypeToken;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.converter.DataConverterException;
import com.uber.cadence.internal.common.InternalUtils;
import com.uber.cadence.internal.replay.ReplayWorkflow;
import com.uber.cadence.internal.replay.ReplayWorkflowFactory;
import com.uber.cadence.internal.worker.WorkflowExecutionException;
import com.uber.cadence.workflow.Functions;
import com.uber.cadence.workflow.QueryMethod;
import com.uber.cadence.workflow.SignalMethod;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowInfo;
import com.uber.cadence.workflow.WorkflowMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;

final class POJOWorkflowImplementationFactory implements ReplayWorkflowFactory {

    private static final Logger log = LoggerFactory.getLogger(POJOWorkflowImplementationFactory.class);
    private static final byte[] EMPTY_BLOB = {};

    private DataConverter dataConverter;

    /**
     * Key: workflow type name, Value: function that creates SyncWorkflowDefinition instance.
     */
    private final Map<String, Functions.Func<SyncWorkflowDefinition>> workflowDefinitions = Collections.synchronizedMap(new HashMap<>());
    private final ExecutorService threadPool;

    public POJOWorkflowImplementationFactory(DataConverter dataConverter, ExecutorService threadPool) {
        this.dataConverter = dataConverter;
        this.threadPool = threadPool;
    }

    public void setWorkflowImplementationTypes(Class<?>[] workflowImplementationTypes) {
        workflowDefinitions.clear();
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
                        workflowName = InternalUtils.getSimpleName(method);
                    }
                    if (workflowDefinitions.containsKey(workflowName)) {
                        throw new IllegalStateException(workflowName + " workflow type is already registered with the worker");
                    }
                    workflowDefinitions.put(workflowName, factory);
                    hasWorkflowMethod = true;
                }
                if (signalMethod != null) {
                    if (method.getReturnType() != Void.TYPE) {
                        throw new IllegalArgumentException("Method annotated with @SignalMethod " +
                                "must have void return type: " + method);
                    }
                    String signalName = signalMethod.name();
                    if (signalName.isEmpty()) {
                        signalName = InternalUtils.getSimpleName(method);
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
        }
        if (!hasWorkflowMethod) {
            throw new IllegalArgumentException("Workflow implementation doesn't implement any interface " +
                    "with a workflow method annotated with @WorkflowMethod: " + workflowImplementationClass);
        }
    }

    private SyncWorkflowDefinition getWorkflowDefinition(WorkflowType workflowType) {
        Functions.Func<SyncWorkflowDefinition> factory = workflowDefinitions.get(workflowType.getName());
        if (factory == null) {
            return null;
        }
        try {
            return factory.apply();
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    public void setDataConverter(DataConverter dataConverter) {
        this.dataConverter = dataConverter;
    }

    @Override
    public ReplayWorkflow getWorkflow(WorkflowType workflowType) throws Exception {
        SyncWorkflowDefinition workflow = getWorkflowDefinition(workflowType);
        return new SyncWorkflow(workflow, dataConverter, threadPool);
    }

    @Override
    public boolean isAnyTypeSupported() {
        return !workflowDefinitions.isEmpty();
    }

    private class POJOWorkflowImplementation implements SyncWorkflowDefinition {

        private final Method workflowMethod;
        private final Class<?> workflowImplementationClass;
        private final Map<String, Method> signalHandlers;
        private Object workflow;

        POJOWorkflowImplementation(Method method, Class<?> workflowImplementationClass, Map<String, Method> signalHandlers) {
            this.workflowMethod = method;
            this.workflowImplementationClass = workflowImplementationClass;
            this.signalHandlers = signalHandlers;
        }

        @Override
        public byte[] execute(byte[] input) throws CancellationException, WorkflowExecutionException {
            Object[] args = dataConverter.fromDataArray(input, workflowMethod.getParameterTypes());
            try {
                newInstance();
                Object result = workflowMethod.invoke(workflow, args);
                if (workflowMethod.getReturnType() == Void.TYPE) {
                    return EMPTY_BLOB;
                }
                return dataConverter.toData(result);
            } catch (IllegalAccessException e) {
                throw new Error(mapToWorkflowExecutionException(e, dataConverter));
            } catch (InvocationTargetException e) {
                Throwable targetException = e.getTargetException();
                if (targetException instanceof Error) {
                    throw (Error) targetException;
                }
                // Cancellation should be delivered as it impacts which decision closes a workflow.
                if (targetException instanceof CancellationException) {
                    throw (CancellationException) targetException;
                }
                if (log.isErrorEnabled()) {
                    WorkflowInfo context = Workflow.getWorkflowInfo();
                    log.error("Workflow execution failure " +
                            "WorkflowID=" + context.getWorkflowId()
                            + ", RunID=" + context.getRunId()
                            + ", WorkflowType=" + context.getWorkflowType(), targetException);
                }
                throw mapToWorkflowExecutionException(targetException, dataConverter);
            }
        }

        private void newInstance() {
            if (workflow == null) {
                try {
                    workflow = workflowImplementationClass.newInstance();
                } catch (InstantiationException | IllegalAccessException e) {
                    // Error to fail decision as this can be fixed by a new deployment.
                    throw new Error("Failure instantiating workflow implementation class "
                            + workflowImplementationClass.getName(), e);
                }
                WorkflowInternal.registerQuery(workflow);
            }
        }

        /**
         * Signals that failed to deserialize are logged, but do not lead to workflow or decision failure.
         * Otherwise a single bad signal from CLI would kill any workflow.
         * Not that throwing Error leads to decision being aborted. Throwing any other exception leads to
         * workflow failure.
         * TODO: Unknown and corrupted signals handler in application code or server side DLQ.
         */
        @Override
        public void processSignal(String signalName, byte[] input, long eventId) {
            Method signalMethod = signalHandlers.get(signalName);
            if (signalMethod == null) {
                log.error("Unknown signal: " + signalName + " at eventID " + eventId
                        + ", knownSignals=" + signalHandlers.keySet());
                return;
            }
            Object[] args = dataConverter.fromDataArray(input, signalMethod.getParameterTypes());
            try {
                newInstance();
                signalMethod.invoke(workflow, args);
            } catch (IllegalAccessException e) {
                throw new Error("Failure processing \"" + signalName
                        + "\" at eventID " + eventId, e);
            } catch (InvocationTargetException e) {
                Throwable targetException = e.getTargetException();
                if (targetException instanceof DataConverterException) {
                    log.error("Failure deserializing signal input for \"" + signalName
                            + "\" at eventID " + eventId + ". Dropping it.", targetException);
                } else {
                    throw new Error("Failure processing \"" + signalName
                            + "\" at eventID " + eventId, targetException);
                }
            }
        }

    }

    public static WorkflowExecutionException mapToWorkflowExecutionException(Throwable e, DataConverter dataConverter) {
        e = CheckedExceptionWrapper.unwrap(e);
        return new WorkflowExecutionException(e.getClass().getName(), dataConverter.toData(e));
    }
}
