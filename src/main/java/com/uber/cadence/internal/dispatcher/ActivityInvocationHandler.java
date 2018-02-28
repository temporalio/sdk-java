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
import com.uber.cadence.activity.ActivityMethod;
import com.uber.cadence.activity.MethodRetry;
import com.uber.cadence.internal.ActivityException;
import com.uber.cadence.internal.common.InternalUtils;
import com.uber.cadence.activity.ActivityOptions;
import com.uber.cadence.workflow.Promise;
import com.uber.cadence.workflow.Workflow;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * Dynamic implementation of a strongly typed child workflow interface.
 */
class ActivityInvocationHandler implements InvocationHandler {

    private final ActivityOptions options;

    ActivityInvocationHandler(ActivityOptions options) {
        this.options = options;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        try {
            if (method.equals(Object.class.getMethod("toString"))) {
                // TODO: activity info
                return "ActivityInvocationHandler";
            }
        } catch (NoSuchMethodException e) {
            throw Workflow.throwWrapped(e);
        }
        if (!method.getDeclaringClass().isInterface()) {
            throw new IllegalArgumentException("Interface type is expected: " + method.getDeclaringClass());
        }
        ActivityMethod activityMethod = method.getAnnotation(ActivityMethod.class);
        String activityName;
        if (activityMethod == null || activityMethod.name().isEmpty()) {
            activityName = InternalUtils.getSimpleName(method);
        } else {
            activityName = activityMethod.name();
        }
        MethodRetry methodRetry = method.getAnnotation(MethodRetry.class);
        ActivityOptions mergedOptions = ActivityOptions.merge(activityMethod, methodRetry, options);
        SyncDecisionContext decisionContext = DeterministicRunnerImpl.currentThreadInternal().getDecisionContext();
        Promise<?> result = decisionContext.executeActivity(activityName, mergedOptions, args, method.getReturnType());
        if (AsyncInternal.isAsync()) {
            AsyncInternal.setAsyncResult(result);
            return Defaults.defaultValue(method.getReturnType());
        }
        try {
            return result.get();
        } catch (ActivityException e) {
            // Reset stack to the current one. Otherwise it is very confusing to see a stack of
            // an event handling method.
            StackTraceElement[] currentStackTrace = Thread.currentThread().getStackTrace();
            e.setStackTrace(currentStackTrace);
            throw e;
        }
    }
}
