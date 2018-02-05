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

import com.google.common.reflect.TypeToken;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowService;
import com.uber.cadence.client.CadenceClient;
import com.uber.cadence.client.CadenceClientOptions;
import com.uber.cadence.client.WorkflowExternalResult;
import com.uber.cadence.internal.DataConverter;
import com.uber.cadence.internal.StartWorkflowOptions;
import com.uber.cadence.internal.worker.GenericWorkflowClientExternalImpl;
import com.uber.cadence.workflow.Functions;
import com.uber.cadence.workflow.QueryMethod;
import com.uber.cadence.workflow.WorkflowMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static com.uber.cadence.internal.common.FlowDefaults.DEFAULT_DATA_CONVERTER;

public final class CadenceClientInternal implements CadenceClient {

    private final GenericWorkflowClientExternalImpl genericClient;
    private final DataConverter dataConverter;

    public CadenceClientInternal(WorkflowService.Iface service, String domain, CadenceClientOptions options) {
        this.genericClient = new GenericWorkflowClientExternalImpl(service, domain);
        if (options == null || options.getDataConverter() == null) {
            this.dataConverter = DEFAULT_DATA_CONVERTER;
        } else {
            this.dataConverter = options.getDataConverter();
        }
    }

    public <T> T newWorkflowStub(Class<T> workflowInterface, StartWorkflowOptions options) {
        checkAnnotation(workflowInterface, WorkflowMethod.class);
        return (T) Proxy.newProxyInstance(WorkflowInternal.class.getClassLoader(),
                new Class<?>[]{workflowInterface},
                new WorkflowInvocationHandler(genericClient, options, dataConverter));
    }

    private <T> void checkAnnotation(Class<T> workflowInterface, Class<? extends Annotation>... annotationClasses) {
        TypeToken<?>.TypeSet interfaces = TypeToken.of(workflowInterface).getTypes().interfaces();
        if (interfaces.isEmpty()) {
            throw new IllegalArgumentException("Workflow must implement at least one interface");
        }
        for (TypeToken<?> i : interfaces) {
            for (Method method : i.getRawType().getMethods()) {
                for (Class<? extends Annotation> annotationClass : annotationClasses) {
                    Object workflowMethod = method.getAnnotation(annotationClass);
                    if (workflowMethod != null) {
                        return;
                    }
                }
            }
        }
        throw new IllegalArgumentException("Workflow interface " + workflowInterface.getName() +
                " doesn't have method annotated with any of " + annotationClasses);
    }

    public <T> T newWorkflowStub(Class<T> workflowInterface, WorkflowExecution execution) {
        checkAnnotation(workflowInterface, WorkflowMethod.class, QueryMethod.class);

        return (T) Proxy.newProxyInstance(WorkflowInternal.class.getClassLoader(),
                new Class<?>[]{workflowInterface},
                new WorkflowInvocationHandler(genericClient, execution, dataConverter));
    }


    public static WorkflowExternalResult<Void> asyncStart(Functions.Proc workflow) {
        WorkflowInvocationHandler.initAsyncInvocation();
        try {
            workflow.apply();
            return WorkflowInvocationHandler.getAsyncInvocationResult();
        } catch (Exception e) {
            return new FailedWorkflowExternalResult<>(e);
        }
    }

    public static <A1> WorkflowExternalResult<Void> asyncStart(Functions.Proc1<A1> workflow, A1 arg1) {
        return asyncStart(() -> workflow.apply(arg1));
    }

    public static <A1, A2> WorkflowExternalResult<Void> asyncStart(Functions.Proc2<A1, A2> workflow, A1 arg1, A2 arg2) {
        return asyncStart(() -> workflow.apply(arg1, arg2));
    }

    public static <A1, A2, A3> WorkflowExternalResult<Void> asyncStart(Functions.Proc3<A1, A2, A3> workflow, A1 arg1, A2 arg2, A3 arg3) {
        return asyncStart(() -> workflow.apply(arg1, arg2, arg3));
    }

    public static <A1, A2, A3, A4> WorkflowExternalResult<Void> asyncStart(Functions.Proc4<A1, A2, A3, A4> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
        return asyncStart(() -> workflow.apply(arg1, arg2, arg3, arg4));
    }

    public static <A1, A2, A3, A4, A5> WorkflowExternalResult<Void> asyncStart(Functions.Proc5<A1, A2, A3, A4, A5> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
        return asyncStart(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5));
    }

    public static <A1, A2, A3, A4, A5, A6> WorkflowExternalResult<Void> asyncStart(Functions.Proc6<A1, A2, A3, A4, A5, A6> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5, A6 arg6) {
        return asyncStart(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5, arg6));
    }

    public static <R> WorkflowExternalResult<R> asyncStart(Functions.Func<R> workflow) {
        return (WorkflowExternalResult<R>) asyncStart(() -> { // Need {} to call asyncStart(Proc...)
            workflow.apply();
        });
    }

    public static <A1, R> WorkflowExternalResult<R> asyncStart(Functions.Func1<A1, R> workflow, A1 arg1) {
        return asyncStart(() -> workflow.apply(arg1));
    }

    public static <A1, A2, R> WorkflowExternalResult<R> asyncStart(Functions.Func2<A1, A2, R> workflow, A1 arg1, A2 arg2) {
        return asyncStart(() -> workflow.apply(arg1, arg2));
    }

    public static <A1, A2, A3, R> WorkflowExternalResult<R> asyncStart(Functions.Func3<A1, A2, A3, R> workflow, A1 arg1, A2 arg2, A3 arg3) {
        return asyncStart(() -> workflow.apply(arg1, arg2, arg3));
    }

    public static <A1, A2, A3, A4, R> WorkflowExternalResult<R> asyncStart(Functions.Func4<A1, A2, A3, A4, R> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
        return asyncStart(() -> workflow.apply(arg1, arg2, arg3, arg4));
    }

    public static <A1, A2, A3, A4, A5, R> WorkflowExternalResult<R> asyncStart(Functions.Func5<A1, A2, A3, A4, A5, R> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
        return asyncStart(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5));
    }

    public static <A1, A2, A3, A4, A5, A6, R> WorkflowExternalResult<R> asyncStart(Functions.Func6<A1, A2, A3, A4, A5, A6, R> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5, A6 arg6) {
        return asyncStart(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5, arg6));
    }
}
