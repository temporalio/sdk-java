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
package com.uber.cadence.internal.common;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * Utility functions shared by the implementation code.
 */
public final class InternalUtils {

    public static final float SECOND = 1000f;

    /**
     * Used to construct default name of an activity or workflow type from a method it implements.
     * @return "Simple class name"::"methodName"
     */
    public static String getSimpleName(Method method) {
        return method.getDeclaringClass().getSimpleName() + "::" + method.getName();
    }

    /**
     * Convert milliseconds to seconds rounding up. Used by timers to ensure that
     * they never fire earlier than requested.
     */
    public static long roundUpToSeconds(Duration duration) {
        return roundUpMillisToSeconds(duration.toMillis());
    }

    /**
     * Convert milliseconds to seconds rounding up. Used by timers to ensure that
     * they never fire earlier than requested.
     */
    public static long roundUpMillisToSeconds(long millis) {
        return (long) Math.ceil(millis / SECOND);
    }

    /**
     * Prohibit instantiation
     */
    private InternalUtils() {}
}
