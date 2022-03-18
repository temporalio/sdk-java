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

package io.temporal.common.metadata;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Metadata of an activity interface.
 *
 * <p>Rules:
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
public final class POJOActivityInterfaceMetadata {

  /** Used to override equals and hashCode of Method to ensure deduping by method name in a set. */
  private static class EqualsByMethodName {
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

  private final Class<?> interfaceClass;
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
    validateModifierAccess(anInterface);

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
    this.interfaceClass = anInterface;
    Map<EqualsByMethodName, POJOActivityMethodMetadata> dedupeMap = new HashMap<>();
    getActivityInterfaceMethods(anInterface, dedupeMap);
    dedupeMap.forEach((k, v) -> methods.put(k.getMethod(), v));
  }

  /** Java interface {@code Class} that backs this activity interface. */
  public Class<?> getInterfaceClass() {
    return interfaceClass;
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

  /**
   * Returns methods collected from the {@code current} interface that belong to classed NOT
   * annotated with {@link ActivityInterface} The idea is that such methods should be propagated to
   * a nearest child annotated with {@link ActivityInterface} if it's present
   */
  private static Set<Method> getActivityInterfaceMethods(
      Class<?> current, Map<EqualsByMethodName, POJOActivityMethodMetadata> dedupeMap) {
    ActivityInterface annotation = current.getAnnotation(ActivityInterface.class);
    final boolean isCurrentAnActivityInterface = annotation != null;

    if (isCurrentAnActivityInterface) {
      validateModifierAccess(current);
    }

    // Set to dedupe the same method due to diamond inheritance
    Set<Method> result = new HashSet<>();
    Class<?>[] interfaces = current.getInterfaces();
    for (Class<?> anInterface : interfaces) {
      Set<Method> parentMethods = getActivityInterfaceMethods(anInterface, dedupeMap);
      addParentMethods(parentMethods, current, result);
    }

    Method[] declaredMethods = current.getDeclaredMethods();
    result.addAll(Arrays.asList(declaredMethods));

    if (isCurrentAnActivityInterface) {
      result.stream()
          .filter(POJOActivityInterfaceMetadata::isValidActivityMethod)
          .map(method -> new POJOActivityMethodMetadata(method, current, annotation))
          .forEach(
              methodMetadata ->
                  POJOActivityInterfaceMetadata.dedupeAndAdd(methodMetadata, dedupeMap));

      // the current interface is an ActivityInterface, so we process the collected methods and
      // there is nothing to pass down
      return Collections.emptySet();
    } else {
      return result; // Not annotated just pass all the methods to the child
    }
  }

  private static void dedupeAndAdd(
      POJOActivityMethodMetadata methodMetadata,
      Map<EqualsByMethodName, POJOActivityMethodMetadata> toDedupeMap) {
    EqualsByMethodName wrapped = new EqualsByMethodName(methodMetadata.getMethod());
    POJOActivityMethodMetadata registeredBefore = toDedupeMap.put(wrapped, methodMetadata);
    if (registeredBefore != null) {
      throw new IllegalArgumentException(
          "Duplicated methods (overloads are not allowed in activity interfaces): \""
              + registeredBefore.getMethod()
              + " through \""
              + registeredBefore.getInterfaceType()
              + "\" and \""
              + methodMetadata.getMethod()
              + "\" through \""
              + methodMetadata.getInterfaceType()
              + "\"");
    }
  }

  private static void addParentMethods(
      Set<Method> parentMethods, Class<?> currentInterface, Set<Method> toSet) {
    for (Method parentMethod : parentMethods) {
      ActivityMethod activityMethod = parentMethod.getAnnotation(ActivityMethod.class);
      if (activityMethod == null) {
        try {
          currentInterface.getDeclaredMethod(
              parentMethod.getName(), parentMethod.getParameterTypes());
          // Don't add to result as it is redefined by current.
          // This allows overriding methods without annotation with annotated methods.
        } catch (NoSuchMethodException e) {
          // current interface doesn't have an override for this method - add it from the parent
          toSet.add(parentMethod);
        }
      } else {
        // parent interface method is explicitly annotated with @ActivityMethod - adding to the
        // result
        toSet.add(parentMethod);
      }
    }
  }

  private static void validateModifierAccess(Class<?> activityInterface) {
    if (!Modifier.isPublic(activityInterface.getModifiers())) {
      throw new IllegalArgumentException(
          "Interface with @ActivityInterface annotation must be public: " + activityInterface);
    }
  }

  private static boolean isValidActivityMethod(Method method) {
    return !method.isSynthetic() && !Modifier.isStatic(method.getModifiers());
  }
}
