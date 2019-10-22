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

import com.google.common.base.Defaults;
import com.uber.cadence.SearchAttributes;
import com.uber.cadence.TaskList;
import com.uber.cadence.TaskListKind;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.converter.JsonDataConverter;
import com.uber.cadence.internal.worker.Shutdownable;
import com.uber.cadence.workflow.WorkflowMethod;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Utility functions shared by the implementation code. */
public final class InternalUtils {

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

  public static TaskList createStickyTaskList(String taskListName) {
    TaskList tl = new TaskList();
    tl.setName(taskListName);
    tl.setKind(TaskListKind.STICKY);
    return tl;
  }

  public static TaskList createNormalTaskList(String taskListName) {
    TaskList tl = new TaskList();
    tl.setName(taskListName);
    tl.setKind(TaskListKind.NORMAL);
    return tl;
  }

  public static long awaitTermination(Shutdownable s, long timeoutMillis) {
    if (s == null) {
      return timeoutMillis;
    }
    return awaitTermination(
        timeoutMillis,
        () -> {
          s.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
        });
  }

  public static long awaitTermination(ExecutorService s, long timeoutMillis) {
    if (s == null) {
      return timeoutMillis;
    }
    return awaitTermination(
        timeoutMillis,
        () -> {
          try {
            s.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
          } catch (InterruptedException e) {
          }
        });
  }

  public static long awaitTermination(long timeoutMillis, Runnable toTerminate) {
    long started = System.currentTimeMillis();
    toTerminate.run();
    long remainingTimeout = timeoutMillis - (System.currentTimeMillis() - started);
    if (remainingTimeout < 0) {
      remainingTimeout = 0;
    }
    return remainingTimeout;
  }

  public static Object getValueOrDefault(Object value, Class<?> valueClass) {
    if (value != null) {
      return value;
    }
    return Defaults.defaultValue(valueClass);
  }

  public static SearchAttributes convertMapToSearchAttributes(
      Map<String, Object> searchAttributes) {
    DataConverter converter = JsonDataConverter.getInstance();
    Map<String, ByteBuffer> mapOfByteBuffer = new HashMap<>();
    searchAttributes.forEach(
        (key, value) -> {
          mapOfByteBuffer.put(key, ByteBuffer.wrap(converter.toData(value)));
        });
    return new SearchAttributes().setIndexedFields(mapOfByteBuffer);
  }

  /** Prohibit instantiation */
  private InternalUtils() {}
}
