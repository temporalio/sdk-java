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

import com.google.common.reflect.TypeToken;
import com.uber.cadence.ActivityType;
import com.uber.cadence.PollForActivityTaskResponse;
import com.uber.cadence.WorkflowService;
import com.uber.cadence.activity.DoNotCompleteOnReturn;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.internal.common.FlowHelpers;
import com.uber.cadence.internal.generic.ActivityImplementation;
import com.uber.cadence.internal.generic.ActivityImplementationFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;

class POJOActivityImplementationFactory implements ActivityImplementationFactory {

    private static final byte[] EMPTY_BLOB = {};
    private DataConverter dataConverter;
    private final Map<String, ActivityImplementation> activities = Collections.synchronizedMap(new HashMap<>());

    POJOActivityImplementationFactory(DataConverter dataConverter) {
        this.dataConverter = dataConverter;
    }

    public DataConverter getDataConverter() {
        return dataConverter;
    }

    public void setDataConverter(DataConverter dataConverter) {
        this.dataConverter = dataConverter;
    }

    public void addActivityImplementation(Object activity) {
        Class<?> cls = activity.getClass();
        TypeToken<?>.TypeSet interfaces = TypeToken.of(cls).getTypes().interfaces();
        if (interfaces.isEmpty()) {
            throw new IllegalArgumentException("Activity must implement at least one interface");
        }
        for (TypeToken<?> i : interfaces) {
            for (Method method : i.getRawType().getMethods()) {
                ActivityImplementation implementation = new POJOActivityImplementation(method, activity);
                activities.put(FlowHelpers.getSimpleName(method), implementation);
            }
        }
    }

    private ActivityExecutionException mapToActivityFailure(Throwable e) {
        if (e instanceof CancellationException) {
            throw (CancellationException) e;
        }
        return new ActivityExecutionException(e.getClass().getName(), dataConverter.toData(e));
    }

    @Override
    public ActivityImplementation getActivityImplementation(ActivityType activityType) {
        return activities.get(activityType.getName());
    }

    @Override
    public boolean isAnyTypeSupported() {
        return !activities.isEmpty();
    }

    public void setActivitiesImplementation(Object[] activitiesImplementation) {
        activities.clear();
        for (Object activity : activitiesImplementation) {
            addActivityImplementation(activity);
        }
    }

    private class POJOActivityImplementation implements ActivityImplementation {
        private final Method method;
        private final Object activity;
        private boolean doNotCompleteOnReturn;

        POJOActivityImplementation(Method interfaceMethod, Object activity) {
            this.method = interfaceMethod;

            // @DoNotCompleteOnReturn is expected to be on implementation method, not the interface.
            // So lookup method starting from the implementation object class.
            DoNotCompleteOnReturn annotation;
            try {
                Method implementationMethod = activity.getClass().getMethod(interfaceMethod.getName(), interfaceMethod.getParameterTypes());
                annotation = implementationMethod.getAnnotation(DoNotCompleteOnReturn.class);
                if (interfaceMethod.getAnnotation(DoNotCompleteOnReturn.class) != null) {
                    throw new IllegalArgumentException("Found @" + DoNotCompleteOnReturn.class.getSimpleName() +
                            " annotation on activity interface method \"" + interfaceMethod +
                            "\". This annotation applies only to activity implementation methods. " +
                            "Try moving it to \"" + implementationMethod + "\"");
                }
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("No implementation method?", e);
            }
            if (annotation != null) {
                doNotCompleteOnReturn = true;
            }
            this.activity = activity;
        }

        /**
         * TODO: Annotation that contains the execution options.
         */
        @Override
        public ActivityTypeExecutionOptions getExecutionOptions() {
            ActivityTypeExecutionOptions result = new ActivityTypeExecutionOptions();
            result.setDoNotCompleteOnReturn(doNotCompleteOnReturn);
            return result;
        }

        @Override
        public byte[] execute(WorkflowService.Iface service, String domain, PollForActivityTaskResponse task) {
            ActivityExecutionContext context = new ActivityExecutionContextImpl(service, domain, task, dataConverter);
            byte[] input = task.getInput();
            Object[] args = dataConverter.fromDataArray(input, method.getParameterTypes());
            CurrentActivityExecutionContext.set(context);
            try {
                Object result = method.invoke(activity, args);
                if (doNotCompleteOnReturn || method.getReturnType() == Void.TYPE) {
                    return EMPTY_BLOB;
                }
                return dataConverter.toData(result);
            } catch (RuntimeException e) {
                throw mapToActivityFailure(e);
            } catch (InvocationTargetException e) {
                throw mapToActivityFailure(e.getTargetException());
            } catch (IllegalAccessException e) {
                throw mapToActivityFailure(e);
            } finally {
                CurrentActivityExecutionContext.unset();
            }
        }
    }
}
