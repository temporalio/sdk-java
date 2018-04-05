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

import com.uber.cadence.workflow.WorkflowMethod;
import java.lang.reflect.Method;
import java.time.Duration;

/** Utility functions shared by the implementation code. */
public final class InternalUtils {

  public static final float SECOND = 1000f;

  /**
   * Used to construct default name of an activity or workflow type from a method it implements.
   *
   * @return "Simple class name"::"methodName"
   */
  public static String getSimpleName(Method method) {
    return method.getDeclaringClass().getSimpleName() + "::" + method.getName();
  }

  public static String getWorkflowType(Method method, WorkflowMethod workflowMethod) {
    String workflowName = workflowMethod.name();
    if (workflowName.isEmpty()) {
      return InternalUtils.getSimpleName(method);
    } else {
      return workflowName;
    }
  }

  public static Method getWorkflowMethod(Class<?> workflowInterface) {
    Method result = null;
    for (Method m : workflowInterface.getMethods()) {
      if (m.getAnnotation(WorkflowMethod.class) != null) {
        if (result != null) {
          throw new IllegalArgumentException(
              "Workflow interface must have exactly one method "
                  + "annotated with @WorkflowMethod. Found \""
                  + result
                  + "\" and \""
                  + m
                  + "\"");
        }
        result = m;
      }
    }
    if (result == null) {
      throw new IllegalArgumentException(
          "Method annotated with @WorkflowMethod is not " + "found at " + workflowInterface);
    }
    return result;
  }

  /**
   * Convert milliseconds to seconds rounding up. Used by timers to ensure that they never fire
   * earlier than requested.
   */
  public static Duration roundUpToSeconds(Duration duration, Duration defaultValue) {
    if (duration == null) {
      return defaultValue;
    }
    return roundUpToSeconds(duration);
  }

  /**
   * Round durations to seconds rounding up. As all timeouts and timers resolution is in seconds
   * ensures that nothing times out or fires before the requested time.
   */
  public static Duration roundUpToSeconds(Duration duration) {
    if (duration == null) {
      return Duration.ZERO;
    }
    Duration result = Duration.ofMillis((long) (Math.ceil(duration.toMillis() / SECOND) * SECOND));
    return result;
  }

  /** Prohibit instantiation */
  private InternalUtils() {}
}
