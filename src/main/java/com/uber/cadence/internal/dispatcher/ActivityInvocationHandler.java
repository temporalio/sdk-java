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
import com.uber.cadence.internal.ActivityException;
import com.uber.cadence.internal.common.InternalUtils;
import com.uber.cadence.workflow.ActivityOptions;
import com.uber.cadence.workflow.Promise;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dynamic implementation of a strongly typed child workflow interface.
 */
class ActivityInvocationHandler extends AsyncInvocationHandler {

    private final ActivityOptions options;

    ActivityInvocationHandler(ActivityOptions options) {
        // Default task list to the same name as the workflow one.
        if (options.getTaskList() == null) {
            String workflowTaskList = WorkflowInternal.getContext().getTaskList();
            this.options = new ActivityOptions.Builder(options).setTaskList(workflowTaskList).build();
        } else {
            this.options = options;
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        ActivityMethod activityMethod = method.getAnnotation(ActivityMethod.class);
        String activityName;
        if (activityMethod == null || activityMethod.name().isEmpty()) {
            activityName = InternalUtils.getSimpleName(method);
        } else {
            activityName = activityMethod.name();
        }
        SyncDecisionContext decisionContext = WorkflowThreadInternal.currentThreadInternal().getDecisionContext();
        AtomicReference<Promise<?>> async = asyncResult.get();
        Promise<?> result = decisionContext.executeActivity(activityName, options, args, method.getReturnType());
        if (async != null) {
            async.set(result);
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
