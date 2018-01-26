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
package com.uber.cadence.common;

import java.lang.reflect.Method;

public final class FlowHelpers {
    
    public static Object[] validateInput(Method method, Object[] args) {
        Class<?>[] paramterTypes = method.getParameterTypes();
        int numberOfParameters = paramterTypes.length;
        if (args == null || args.length != numberOfParameters) {
            throw new IllegalStateException("Number of parameters does not match args size.");
        }
        
        int index = 0;
        for (Class<?> paramType: paramterTypes) {
            Object argument = args[index];
            if (argument != null && !paramType.isAssignableFrom(argument.getClass())) {
                throw new IllegalStateException("Param type '" + paramType.getName() + "' is not assigable from '" 
                        + argument.getClass().getName() + "'.");
            }
            
            index++;
        }
        
        return args;
    }

    public static String getSimpleName(Method method) {
        return method.getDeclaringClass().getSimpleName() + "::" + method.getName();
    }

//    public static String taskPriorityToString(Integer taskPriority) {
//        if (taskPriority == null) {
//            return null;
//        }
//        return String.valueOf(taskPriority);
//    }
//
//    public static int taskPriorityToInt(String taskPriority) {
//        if (taskPriority == null) {
//            return FlowConstants.DEFAULT_TASK_PRIORITY;
//        }
//        else {
//            return Integer.parseInt(taskPriority);
//        }
//    }
    
}
