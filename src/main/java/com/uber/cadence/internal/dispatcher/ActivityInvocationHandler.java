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

import com.google.common.base.Defaults;
import com.uber.cadence.workflow.ActivitySchedulingOptions;
import com.uber.cadence.internal.common.FlowHelpers;
import com.uber.cadence.workflow.WorkflowFuture;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

class ActivityInvocationHandler implements InvocationHandler {

    private static final ThreadLocal<AtomicReference<WorkflowFuture>> asyncResult = new ThreadLocal<>();
    private final ActivitySchedulingOptions options;

    public ActivityInvocationHandler(ActivitySchedulingOptions options) {
        this.options = options;
    }

    public static void initAsyncInvocation() {
        if (asyncResult.get() != null) {
            throw new IllegalStateException("already in async invocation");
        }
        asyncResult.set(new AtomicReference<>());
    }

    public static WorkflowFuture getAsyncInvocationResult() {
        try {
            AtomicReference<WorkflowFuture> reference = asyncResult.get();
            if (reference == null) {
                throw new IllegalStateException("initAsyncInvocation wasn't called");
            }
            WorkflowFuture result = reference.get();
            if (result == null) {
                throw new IllegalStateException("async result wasn't set");
            }
            return result;
        } finally {
            asyncResult.remove();
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        SyncDecisionContext decisionContext = WorkflowThreadInternal.currentThreadInternal().getDecisionContext();
        // TODO: Add annotation to support overriding activity name.
        String activityName = FlowHelpers.getSimpleName(method);
        AtomicReference<WorkflowFuture> async = asyncResult.get();
        if (async != null) {
            async.set(decisionContext.executeActivityAsync(activityName, options, args, method.getReturnType()));
            return Defaults.defaultValue(method.getReturnType());
        }
        return decisionContext.executeActivity(activityName, options, args, method.getReturnType());
    }

}
