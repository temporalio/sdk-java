/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
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

package io.temporal.internal.common;

import com.google.common.base.Defaults;
import com.google.common.base.Objects;
import com.google.protobuf.ByteString;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.JsonDataConverter;
import io.temporal.internal.worker.Shutdownable;
import io.temporal.proto.common.SearchAttributes;
import io.temporal.proto.common.TaskList;
import io.temporal.proto.enums.TaskListKind;
import io.temporal.workflow.WorkflowMethod;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Utility functions shared by the implementation code. */
public final class InternalUtils {

  public static class MethodInterfacePair {
    private final Method method;
    private final Class<?> type;

    MethodInterfacePair(Method method, Class<?> type) {
      this.method = method;
      this.type = type;
    }

    public Method getMethod() {
      return method;
    }

    public Class<?> getType() {
      return type;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      MethodInterfacePair that = (MethodInterfacePair) o;
      return Objects.equal(method, that.method) && Objects.equal(type, that.type);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(method, type);
    }

    @Override
    public String toString() {
      return "MethodInterfacePair{" + "method=" + method + ", type=" + type + '}';
    }
  }

  /** Used to override equals and hashCode of Method to ensure deduping by method name in a set. */
  static class MethodWrapper {
    private final Method method;

    MethodWrapper(Method method) {
      this.method = method;
    }

    public Method getMethod() {
      return method;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      MethodWrapper that = (MethodWrapper) o;
      return Objects.equal(method.getName(), that.method.getName());
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(method.getName());
    }
  }

  /**
   * Used to construct default name of an activity or workflow type from a method it implements.
   *
   * @return "Simple class name"_"methodName"
   */
  public static String getSimpleName(MethodInterfacePair pair) {
    return pair.getType().getSimpleName() + "_" + pair.getMethod().getName();
  }

  public static String getWorkflowType(MethodInterfacePair pair, WorkflowMethod workflowMethod) {
    String workflowName = workflowMethod.name();
    if (workflowName.isEmpty()) {
      return getSimpleName(pair);
    } else {
      return workflowName;
    }
  }

  public static MethodInterfacePair getWorkflowMethod(
      Class<?> workflowInterface, Set<MethodInterfacePair> workflowMethods) {
    MethodInterfacePair result = null;
    for (MethodInterfacePair pair : workflowMethods) {
      Method m = pair.getMethod();
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
        result = pair;
      }
    }
    if (result == null) {
      throw new IllegalArgumentException(
          "Method annotated with @WorkflowMethod is not " + "found at " + workflowInterface);
    }
    return result;
  }

  public static TaskList createStickyTaskList(String taskListName) {
    return TaskList.newBuilder()
        .setName(taskListName)
        .setKind(TaskListKind.TaskListKindSticky)
        .build();
  }

  public static TaskList createNormalTaskList(String taskListName) {
    return TaskList.newBuilder()
        .setName(taskListName)
        .setKind(TaskListKind.TaskListKindNormal)
        .build();
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
    Map<String, ByteString> mapOfByteBuffer = new HashMap<>();
    searchAttributes.forEach(
        (key, value) -> {
          mapOfByteBuffer.put(key, ByteString.copyFrom(converter.toData(value)));
        });
    return SearchAttributes.newBuilder().putAllIndexedFields(mapOfByteBuffer).build();
  }

  public static String getEntityName(Method method, Class<? extends Annotation> annotation) {
    MethodInterfacePair pair = getMethodInterfacePair(method, annotation);
    return getSimpleName(pair);
  }

  public static InternalUtils.MethodInterfacePair getMethodInterfacePair(
      Method method, Class<? extends Annotation> annotation) {
    Set<InternalUtils.MethodInterfacePair> methods =
        getAnnotatedInterfaceMethodsFromInterface(method.getDeclaringClass(), annotation);
    InternalUtils.MethodInterfacePair result = null;
    for (InternalUtils.MethodInterfacePair pair : methods) {
      if (pair.getMethod().equals(method)) {
        result = pair;
        break;
      }
    }
    if (result == null) {
      throw new IllegalStateException("Unexpected");
    }
    return result;
  }

  public static Set<MethodInterfacePair> getAnnotatedInterfaceMethodsFromImplementation(
      Class<?> implementationClass, Class<? extends Annotation> annotationClass) {
    if (implementationClass.isInterface()) {
      throw new IllegalArgumentException(
          "Concrete class expected. Found interface: " + implementationClass.getSimpleName());
    }
    Set<MethodInterfacePair> pairs = new HashSet<>();
    // Methods inherited from interfaces that are not annotated with @ActivityInterface
    Set<MethodWrapper> ignored = new HashSet<>();
    getAnnotatedInterfaceMethodsFromImplementation(
        implementationClass, annotationClass, ignored, pairs);
    return pairs;
  }

  public static Set<MethodInterfacePair> getAnnotatedInterfaceMethodsFromInterface(
      Class<?> iClass, Class<? extends Annotation> annotationClass) {
    if (!iClass.isInterface()) {
      throw new IllegalArgumentException("Interface expected. Found: " + iClass.getSimpleName());
    }
    Annotation annotation = iClass.getAnnotation(annotationClass);
    if (annotation == null) {
      throw new IllegalArgumentException(
          "@ActivityInterface annotation is required on the stub interface: "
              + iClass.getSimpleName());
    }
    Set<MethodInterfacePair> pairs = new HashSet<>();
    // Methods inherited from interfaces that are not annotated with @ActivityInterface
    Set<MethodWrapper> ignored = new HashSet<>();
    getAnnotatedInterfaceMethodsFromImplementation(iClass, annotationClass, ignored, pairs);
    if (!ignored.isEmpty()) {
      throw new IllegalStateException("Not empty ignored: " + ignored);
    }
    return pairs;
  }

  private static void getAnnotatedInterfaceMethodsFromImplementation(
      Class<?> current,
      Class<? extends Annotation> annotationClass,
      Set<MethodWrapper> methods,
      Set<MethodInterfacePair> result) {
    // Using set to dedupe methods which are defined in both non activity parent and current
    Set<MethodWrapper> ourMethods = new HashSet<>();
    if (current.isInterface()) {
      Method[] declaredMethods = current.getDeclaredMethods();
      for (int i = 0; i < declaredMethods.length; i++) {
        Method declaredMethod = declaredMethods[i];
        ourMethods.add(new MethodWrapper(declaredMethod));
      }
    }
    Class<?>[] interfaces = current.getInterfaces();
    for (int i = 0; i < interfaces.length; i++) {
      Class<?> anInterface = interfaces[i];
      getAnnotatedInterfaceMethodsFromImplementation(
          anInterface, annotationClass, ourMethods, result);
    }
    Annotation annotation = current.getAnnotation(annotationClass);
    if (annotation == null) {
      methods.addAll(ourMethods);
      return;
    }
    for (MethodWrapper method : ourMethods) {
      result.add(new MethodInterfacePair(method.getMethod(), current));
    }
  }

  /** Prohibit instantiation */
  private InternalUtils() {}
}
