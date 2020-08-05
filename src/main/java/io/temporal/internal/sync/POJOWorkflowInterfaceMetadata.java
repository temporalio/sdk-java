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

import io.temporal.workflow.WorkflowInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Rules:
 *
 * <ul>
 *   <li>A workflow implementation must implement at least one non empty interface annotated with
 *       WorkflowInterface
 *   <li>An interface annotated with WorkflowInterface can extend zero or more interfaces.
 *   <li>An interface annotated with WorkflowInterface defines workflow methods for all methods it
 *       inherited from interfaces which are not annotated with WorkflowInterface.
 *   <li>Each method name can be defined only once across all interfaces annotated with
 *       WorkflowInterface. So if annotated interface A has method foo() and an annotated interface
 *       B extends A it cannot also declare foo() even with a different signature.
 * </ul>
 */
class POJOWorkflowInterfaceMetadata {

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

  static class EqualsByNameType {
    private final String name;
    private final WorkflowMethodType type;

    EqualsByNameType(String name, WorkflowMethodType type) {
      this.name = name;
      this.type = type;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      EqualsByNameType that = (EqualsByNameType) o;
      return com.google.common.base.Objects.equal(name, that.name) && type == that.type;
    }

    @Override
    public int hashCode() {
      return com.google.common.base.Objects.hashCode(name, type);
    }
  }

  private POJOWorkflowMethodMetadata workflowMethod;
  private final Map<Method, POJOWorkflowMethodMetadata> methods = new HashMap<>();

  public static POJOWorkflowInterfaceMetadata newInstance(Class<?> anInterface) {
    return newInstance(anInterface, true);
  }

  public static POJOWorkflowInterfaceMetadata newInstanceSkipWorkflowAnnotationCheck(
      Class<?> anInterface) {
    return newInstance(anInterface, false);
  }

  private static POJOWorkflowInterfaceMetadata newInstance(
      Class<?> anInterface, boolean checkWorkflowInterfaceAnnotation) {
    if (!anInterface.isInterface()) {
      throw new IllegalArgumentException("Not an interface: " + anInterface);
    }
    if (checkWorkflowInterfaceAnnotation) {
      WorkflowInterface annotation = anInterface.getAnnotation(WorkflowInterface.class);
      if (annotation == null) {
        throw new IllegalArgumentException(
            "Missing required @WorkflowInterface annotation: " + anInterface);
      }
      validatePublicModifier(anInterface);
    }
    POJOWorkflowInterfaceMetadata result = new POJOWorkflowInterfaceMetadata(anInterface, false);
    if (result.methods.isEmpty()) {
      if (checkWorkflowInterfaceAnnotation) {
        throw new IllegalArgumentException(
            "Interface doesn't contain any methods: " + anInterface.getName());
      }
    }
    return result;
  }

  private static void validatePublicModifier(Class<?> anInterface) {
    if (!Modifier.isPublic(anInterface.getModifiers())) {
      throw new IllegalArgumentException(
          "Interface with @WorkflowInterface annotation must be public: " + anInterface);
    }
  }

  static POJOWorkflowInterfaceMetadata newImplementationInterface(Class<?> anInterface) {
    return new POJOWorkflowInterfaceMetadata(anInterface, true);
  }

  /** @param implementation if the metadata is for a workflow implementation class vs stub. */
  private POJOWorkflowInterfaceMetadata(Class<?> anInterface, boolean implementation) {
    Map<EqualsByMethodName, Method> dedupeMap = new HashMap<>();
    getWorkflowInterfaceMethods(anInterface, !implementation, dedupeMap);
  }

  public Optional<POJOWorkflowMethodMetadata> getWorkflowMethod() {
    return Optional.ofNullable(workflowMethod);
  }

  public Optional<String> getWorkflowType() {
    if (workflowMethod == null) {
      return Optional.empty();
    }
    return Optional.of(workflowMethod.getName());
  }

  public POJOWorkflowMethodMetadata getMethodMetadata(Method method) {
    POJOWorkflowMethodMetadata result = methods.get(method);
    if (result == null) {
      throw new IllegalArgumentException("Unknown method: " + method);
    }
    return result;
  }

  public List<POJOWorkflowMethodMetadata> getMethodsMetadata(WorkflowMethodType type) {
    List<POJOWorkflowMethodMetadata> result = new ArrayList<>();
    for (POJOWorkflowMethodMetadata methodMetadata : this.methods.values()) {
      if (methodMetadata.getType() == type) {
        result.add(methodMetadata);
      }
    }
    return result;
  }

  public List<POJOWorkflowMethodMetadata> getMethodsMetadata() {
    return new ArrayList<>(this.methods.values());
  }

  /** @return methods which are not part of an interface annotated with WorkflowInterface */
  private Set<POJOWorkflowMethod> getWorkflowInterfaceMethods(
      Class<?> current, boolean rootClass, Map<EqualsByMethodName, Method> dedupeMap) {
    WorkflowInterface annotation = current.getAnnotation(WorkflowInterface.class);

    if (annotation != null) {
      validatePublicModifier(current);
    }

    // Set to de-dupe the same method due to diamond inheritance
    Set<POJOWorkflowMethod> result = new HashSet<>();
    Class<?>[] interfaces = current.getInterfaces();
    for (int i = 0; i < interfaces.length; i++) {
      Class<?> anInterface = interfaces[i];
      Set<POJOWorkflowMethod> parentMethods =
          getWorkflowInterfaceMethods(anInterface, false, dedupeMap);
      for (POJOWorkflowMethod parentMethod : parentMethods) {
        if (parentMethod.getType() == WorkflowMethodType.NONE) {
          Method method = parentMethod.getMethod();
          try {
            current.getMethod(method.getName(), method.getParameterTypes());
            // Don't add to result as it is redefined by current.
            // This allows overriding methods without annotation with annotated methods.
            continue;
          } catch (NoSuchMethodException e) {
            if (annotation != null) {
              throw new IllegalArgumentException(
                  "Missing @WorkflowMethod, @SignalMethod or @QueryMethod annotation on " + method);
            }
          }
        }
        result.add(parentMethod);
      }
    }
    Method[] declaredMethods = current.getDeclaredMethods();
    for (int i = 0; i < declaredMethods.length; i++) {
      Method declaredMethod = declaredMethods[i];
      POJOWorkflowMethod methodMetadata = new POJOWorkflowMethod(declaredMethod);
      result.add(methodMetadata);
    }
    if (annotation == null && !rootClass) {
      return result; // Not annotated just pass all the methods to the parent
    }
    for (POJOWorkflowMethod workflowMethod : result) {
      Method method = workflowMethod.getMethod();
      if (workflowMethod.getType() == WorkflowMethodType.NONE) {
        if (annotation != null) {
          throw new IllegalArgumentException(
              "Missing @WorkflowMethod, @SignalMethod or @QueryMethod annotation on " + method);
        }
      }
      EqualsByMethodName wrapped = new EqualsByMethodName(method);
      Method registered = dedupeMap.put(wrapped, method);
      if (registered != null && !registered.equals(method)) {
        throw new IllegalArgumentException(
            "Duplicated methods (overloads are not allowed): \""
                + registered
                + "\" and \""
                + method
                + "\"");
      }

      if (workflowMethod.getType() == WorkflowMethodType.NONE && annotation == null) {
        continue;
      }

      POJOWorkflowMethodMetadata methodMetadata =
          new POJOWorkflowMethodMetadata(workflowMethod, current);
      if (workflowMethod.getType() == WorkflowMethodType.WORKFLOW) {
        if (this.workflowMethod != null) {
          throw new IllegalArgumentException(
              "Duplicated @WorkflowMethod: "
                  + workflowMethod.getMethod()
                  + " and "
                  + this.workflowMethod.getWorkflowMethod());
        }
        this.workflowMethod = methodMetadata;
      }
      methods.put(method, methodMetadata);
    }
    return Collections.emptySet();
  }
}
