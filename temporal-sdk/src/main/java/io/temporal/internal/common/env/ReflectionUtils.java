/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.common.env;

import com.google.common.base.Joiner;
import io.temporal.workflow.WorkflowInit;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class ReflectionUtils {
  private ReflectionUtils() {}

  public static Optional<Constructor<?>> getWorkflowInitConstructor(
      Class<?> clazz, List<Method> workflowMethod) {
    // We iterate through all constructors to find the one annotated with @WorkflowInit
    // and check if it has the same parameters as the workflow method.
    // We check all declared constructors to find any constructors that are annotated with
    // @WorkflowInit, but are not public, to give a more informative error message.
    Optional<Constructor<?>> workflowInit = Optional.empty();
    for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
      WorkflowInit wfInit = ctor.getAnnotation(WorkflowInit.class);
      if (wfInit == null) {
        continue;
      }
      if (workflowMethod.size() != 1) {
        throw new IllegalArgumentException(
            "Multiple interfaces implemented while using @WorkflowInit annotation. Only one is allowed: "
                + clazz.getName());
      }
      if (workflowInit.isPresent()) {
        throw new IllegalArgumentException(
            "Multiple constructors annotated with @WorkflowInit found. Only one is allowed: "
                + clazz.getName());
      }
      if (!Modifier.isPublic(ctor.getModifiers())) {
        throw new IllegalArgumentException(
            "Constructor with @WorkflowInit annotation must be public: " + clazz.getName());
      }
      if (!Arrays.equals(
          ctor.getGenericParameterTypes(), workflowMethod.get(0).getGenericParameterTypes())) {
        throw new IllegalArgumentException(
            "Constructor annotated with @WorkflowInit must have the same parameters as the workflow method: "
                + clazz.getName());
      }
      workflowInit = Optional.of(ctor);
    }
    return workflowInit;
  }

  public static Optional<Constructor<?>> getPublicDefaultConstructor(Class<?> clazz) {
    Constructor<?> defaultConstructors = null;
    for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
      if (ctor.getParameterCount() != 0) {
        continue;
      }
      if (!Modifier.isPublic(ctor.getModifiers())) {
        throw new IllegalArgumentException(
            "Default constructor must be public: " + clazz.getName());
      }
      defaultConstructors = ctor;
      break;
    }
    return Optional.ofNullable(defaultConstructors);
  }

  public static String getMethodNameForStackTraceCutoff(
      Class<?> clazz, String methodName, Class<?>... parameterTypes) throws RuntimeException {
    try {
      return clazz.getName() + "." + clazz.getMethod(methodName, parameterTypes).getName();
    } catch (NoSuchMethodException e) {
      throw new RuntimeException(
          "Reflection code that publishes the methods signatures is out of sync with actual method signatures. Class '"
              + clazz.getCanonicalName()
              + "' is expected to have method '"
              + methodName
              + "' with parameters {"
              + Joiner.on(", ").join(parameterTypes)
              + "}",
          e);
    }
  }
}
