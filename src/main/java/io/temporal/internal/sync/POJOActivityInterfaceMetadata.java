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

package io.temporal.internal.sync;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rules:
 *
 * <ul>
 *   <li>An activity implementation must implement at least one non empty interface annotated with
 *       ActivityInterface
 *   <li>An interface annotated with ActivityInterface can extend zero or more interfaces.
 *   <li>An interface annotated with ActivityInterface defines activity methods for all methods it
 *       inherited from interfaces which are not annotated with ActivityInterface.
 *   <li>Each method name can be defined only once across all interfaces annotated with
 *       ActivityInterface. So if annotated interface A has method foo() and an annotated interface
 *       B extends A it cannot also declare foo() even with a different signature.
 * </ul>
 */
class POJOActivityInterfaceMetadata {

  /** Used to override equals and hashCode of Method to ensure deduping by method name in a set. */
  static class EqualsByMethodName {
    private final Method method;

    EqualsByMethodName(Method method) {
      this.method = method;
    }

    public Method getMethod() {
      return method;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      EqualsByMethodName that = (EqualsByMethodName) o;
      return com.google.common.base.Objects.equal(method.getName(), that.method.getName());
    }

    @Override
    public int hashCode() {
      return com.google.common.base.Objects.hashCode(method.getName());
    }
  }

  private final Map<Method, POJOActivityMethodMetadata> methods = new HashMap<>();

  public static POJOActivityInterfaceMetadata newInstance(Class<?> anInterface) {
    if (!anInterface.isInterface()) {
      throw new IllegalArgumentException("Interface expected: " + anInterface);
    }
    ActivityInterface annotation = anInterface.getAnnotation(ActivityInterface.class);
    if (annotation == null) {
      throw new IllegalArgumentException(
          "Missing required @ActivityInterface annotation: " + anInterface);
    }
    POJOActivityInterfaceMetadata result = new POJOActivityInterfaceMetadata(anInterface);
    if (result.methods.isEmpty()) {
      throw new IllegalArgumentException(
          "Interface doesn't contain any methods: " + anInterface.getName());
    }
    return result;
  }

  static POJOActivityInterfaceMetadata newImplementationInterface(Class<?> anInterface) {
    return new POJOActivityInterfaceMetadata(anInterface);
  }

  private POJOActivityInterfaceMetadata(Class<?> anInterface) {
    if (!anInterface.isInterface()) {
      throw new IllegalArgumentException("not an interface: " + anInterface);
    }
    Map<EqualsByMethodName, POJOActivityMethodMetadata> dedupeMap = new HashMap<>();
    getActivityInterfaceMethods(anInterface, dedupeMap);
  }

  public List<POJOActivityMethodMetadata> getMethodsMetadata() {
    return new ArrayList<>(methods.values());
  }

  public POJOActivityMethodMetadata getMethodMetadata(Method method) {
    POJOActivityMethodMetadata result = methods.get(method);
    if (result == null) {
      throw new IllegalArgumentException("Unknown method: " + method);
    }
    return result;
  }

  /** @return methods which are not part of an interface annotated with ActivityInterface */
  private Set<Method> getActivityInterfaceMethods(
      Class<?> current, Map<EqualsByMethodName, POJOActivityMethodMetadata> dedupeMap) {
    ActivityInterface annotation = current.getAnnotation(ActivityInterface.class);

    // Set to dedupe the same method due to diamond inheritance
    Set<Method> result = new HashSet<>();
    Class<?>[] interfaces = current.getInterfaces();
    for (int i = 0; i < interfaces.length; i++) {
      Class<?> anInterface = interfaces[i];
      Set<Method> parentMethods = getActivityInterfaceMethods(anInterface, dedupeMap);
      for (Method parentMethod : parentMethods) {
        ActivityMethod activityMethod = parentMethod.getAnnotation(ActivityMethod.class);
        if (activityMethod == null) {
          try {
            current.getDeclaredMethod(parentMethod.getName(), parentMethod.getParameterTypes());
            // Don't add to result as it is redefined by current.
            // This allows overriding methods without annotation with annotated methods.
            continue;
          } catch (NoSuchMethodException e) {
          }
        }
        result.add(parentMethod);
      }
    }
    Method[] declaredMethods = current.getDeclaredMethods();
    for (int i = 0; i < declaredMethods.length; i++) {
      Method declaredMethod = declaredMethods[i];
      result.add(declaredMethod);
    }
    if (annotation == null) {
      return result; // Not annotated just pass all the methods to the parent
    }
    for (Method method : result) {
      POJOActivityMethodMetadata methodMetadata = new POJOActivityMethodMetadata(method, current);
      EqualsByMethodName wrapped = new EqualsByMethodName(method);
      POJOActivityMethodMetadata registered = dedupeMap.put(wrapped, methodMetadata);
      if (registered != null) {
        throw new IllegalArgumentException(
            "Duplicated methods (overloads are not allowed in activity interfaces): \""
                + registered.getMethod()
                + " through \""
                + registered.getInterfaceType()
                + "\" and \""
                + methodMetadata.getMethod()
                + "\" through \""
                + methodMetadata.getInterfaceType()
                + "\"");
      }
      methods.put(method, methodMetadata);
    }
    return Collections.emptySet();
  }
}
