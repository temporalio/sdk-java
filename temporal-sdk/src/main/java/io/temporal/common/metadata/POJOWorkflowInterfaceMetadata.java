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

package io.temporal.common.metadata;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Metadata of a workflow interface.
 *
 * <p>Rules:
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
public final class POJOWorkflowInterfaceMetadata {

  /** Used to override equals and hashCode of Method to ensure deduping by method name in a set. */
  private static class EqualsByName {
    private final Method method;
    private final String nameFromAnnotation;

    EqualsByName(Method method, String nameFromAnnotation) {
      this.method = method;
      this.nameFromAnnotation = nameFromAnnotation;
    }

    public Method getMethod() {
      return method;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      EqualsByName that = (EqualsByName) o;
      return method.equals(that.method)
          && Objects.equals(nameFromAnnotation, that.nameFromAnnotation);
    }

    @Override
    public int hashCode() {
      return Objects.hash(method, nameFromAnnotation);
    }
  }

  private POJOWorkflowMethodMetadata workflowMethod;
  private final Class<?> interfaceClass;
  private final Map<Method, POJOWorkflowMethodMetadata> methods = new HashMap<>();

  /**
   * Returns POJOWorkflowInterfaceMetadata for an interface annotated with {@link
   * WorkflowInterface}.
   */
  public static POJOWorkflowInterfaceMetadata newStubInstance(Class<?> anInterface) {
    return newInstance(anInterface, true, true);
  }

  /**
   * Returns POJOWorkflowInterfaceMetadata for an interface that may be annotated with {@link
   * WorkflowInterface}. This to support invoking workflow signal and query methods through a base
   * interface without such annotation.
   */
  public static POJOWorkflowInterfaceMetadata newStubInstanceSkipWorkflowAnnotationCheck(
      Class<?> anInterface) {
    return newInstance(anInterface, false, true);
  }

  /**
   * Returns POJOWorkflowInterfaceMetadata for an interface annotated with {@link WorkflowInterface}
   * for workflow implementation. The core difference from stub methods that the interfaces passing
   * here can be not {@link WorkflowInterface} at all.
   */
  static POJOWorkflowInterfaceMetadata newImplementationInstance(Class<?> anInterface) {
    return newInstance(anInterface, false, false);
  }

  /**
   * Ensures that the interface that the stub is created with is a {@link WorkflowInterface} with
   * the right structure.
   *
   * @param anInterface interface class
   * @param checkWorkflowInterfaceAnnotation check if the interface has a {@link WorkflowInterface}
   *     annotation with methods
   * @param forceProcessAsWorkflowInterface if true, the {@code current} is processed as a workflow
   *     interface even if it is not annotated with {@link WorkflowInterface}. true for stub
   *     interface if it's used to create a stub.
   */
  private static POJOWorkflowInterfaceMetadata newInstance(
      Class<?> anInterface,
      boolean checkWorkflowInterfaceAnnotation,
      boolean forceProcessAsWorkflowInterface) {
    if (!anInterface.isInterface()) {
      throw new IllegalArgumentException("Not an interface: " + anInterface);
    }
    if (checkWorkflowInterfaceAnnotation) {
      WorkflowInterface annotation = anInterface.getAnnotation(WorkflowInterface.class);
      if (annotation == null) {
        throw new IllegalArgumentException(
            "Missing required @WorkflowInterface annotation: " + anInterface);
      }
      validateModifierAccess(anInterface);
    }
    POJOWorkflowInterfaceMetadata result =
        new POJOWorkflowInterfaceMetadata(anInterface, forceProcessAsWorkflowInterface);
    if (result.methods.isEmpty()) {
      if (checkWorkflowInterfaceAnnotation) {
        throw new IllegalArgumentException(
            "Interface doesn't contain any methods: " + anInterface.getName());
      }
    }
    return result;
  }

  /**
   * @param forceProcessAsWorkflowInterface if true, the {@code current} is processed as a workflow
   *     interface even if it is not annotated with {@link WorkflowInterface}. true for stub
   *     interface if it's used to create a stub.
   */
  private POJOWorkflowInterfaceMetadata(
      Class<?> anInterface, boolean forceProcessAsWorkflowInterface) {
    this.interfaceClass = anInterface;
    Map<EqualsByName, Method> dedupeMap = new HashMap<>();
    getWorkflowInterfaceMethods(anInterface, forceProcessAsWorkflowInterface, dedupeMap);
  }

  public Optional<POJOWorkflowMethodMetadata> getWorkflowMethod() {
    return Optional.ofNullable(workflowMethod);
  }

  /** Java interface {@code Class} that backs this workflow interface. */
  public Class<?> getInterfaceClass() {
    return interfaceClass;
  }

  /**
   * Workflow type the workflow interface defines. It is empty for interfaces that contain only
   * signal and query methods.
   */
  public Optional<String> getWorkflowType() {
    if (workflowMethod == null) {
      return Optional.empty();
    }
    return Optional.of(workflowMethod.getName());
  }

  /**
   * Return metadata for a method of a workflow interface.
   *
   * @throws IllegalArgumentException if method doesn't belong to the workflow interface.
   */
  public POJOWorkflowMethodMetadata getMethodMetadata(Method method) {
    POJOWorkflowMethodMetadata result = methods.get(method);
    if (result == null) {
      throw new IllegalArgumentException("Unknown method: " + method);
    }
    return result;
  }

  public List<POJOWorkflowMethodMetadata> getMethodsMetadata() {
    return new ArrayList<>(this.methods.values());
  }

  /**
   * @param forceProcessAsWorkflowInterface if true, the {@code current} is processed as a workflow
   *     interface even if it is not annotated with {@link WorkflowInterface}. true for stub
   *     interface if it's used to create a stub.
   * @return methods which are not part of an interface annotated with WorkflowInterface
   */
  private Set<POJOWorkflowMethod> getWorkflowInterfaceMethods(
      Class<?> current,
      boolean forceProcessAsWorkflowInterface,
      Map<EqualsByName, Method> dedupeMap) {
    WorkflowInterface annotation = current.getAnnotation(WorkflowInterface.class);

    final boolean isCurrentAWorkflowInterface = annotation != null;

    if (isCurrentAWorkflowInterface) {
      validateModifierAccess(current);
    }

    // Set to de-dupe the same method due to diamond inheritance
    Set<POJOWorkflowMethod> result = new HashSet<>();
    Class<?>[] interfaces = current.getInterfaces();
    for (Class<?> anInterface : interfaces) {
      Set<POJOWorkflowMethod> parentMethods =
          getWorkflowInterfaceMethods(anInterface, false, dedupeMap);
      addParentMethods(parentMethods, current, result);
    }

    Method[] declaredMethods = current.getDeclaredMethods();
    for (Method declaredMethod : declaredMethods) {
      POJOWorkflowMethod methodMetadata = new POJOWorkflowMethod(declaredMethod);
      if (validateAndQualifiedForWorkflowMethod(methodMetadata)) {
        result.add(methodMetadata);
      }
    }

    if (isCurrentAWorkflowInterface || forceProcessAsWorkflowInterface) {
      for (POJOWorkflowMethod workflowMethod : result) {
        if (workflowMethod.getType() == WorkflowMethodType.NONE && isCurrentAWorkflowInterface) {
          // Workflow methods are different from activity methods.
          // Method in a hierarchy of activity interfaces that is not annotated is an activity
          // interface.
          // Method in a hierarchy of workflow interfaces that is not annotated is a mistake.
          throw new IllegalArgumentException(
              "Missing @WorkflowMethod, @SignalMethod or @QueryMethod annotation on "
                  + workflowMethod.getMethod());
        }

        dedupeAndAdd(workflowMethod, dedupeMap);

        if (workflowMethod.getType() != WorkflowMethodType.NONE) {
          POJOWorkflowMethodMetadata methodMetadata =
              new POJOWorkflowMethodMetadata(workflowMethod, current);
          methods.put(workflowMethod.getMethod(), methodMetadata);

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
        }
      }
      // the current interface is a WorkflowInterface, or forced to processed like one,
      // so we process the collected methods and there is nothing to pass down
      return Collections.emptySet();
    } else {
      // Not annotated and forceProcessAsWorkflowInterface is false.
      // Don't verify the integrity of the interface and don't flush the result into internal state.
      // Just pass an accumulated result down
      return result;
    }
  }

  private static void dedupeAndAdd(
      POJOWorkflowMethod workflowMethod, Map<EqualsByName, Method> dedupeMap) {
    Method method = workflowMethod.getMethod();
    EqualsByName wrapped =
        new EqualsByName(method, workflowMethod.getNameFromAnnotation().orElse(null));

    Method registeredBefore = dedupeMap.put(wrapped, method);
    if (registeredBefore != null && !registeredBefore.equals(method)) {
      // TODO POJOActivityInterfaceMetadata is having an access to POJOActivityMethodMetadata
      //  on this stage and it able to provide more data about the conflict, this implementation
      //  should be updated in the same manner
      throw new IllegalArgumentException(
          "Duplicated methods (overloads are not allowed in workflow interfaces): \""
              + registeredBefore
              + "\" and \""
              + method
              + "\"");
    }
  }

  private static void addParentMethods(
      Set<POJOWorkflowMethod> parentMethods,
      Class<?> currentInterface,
      Set<POJOWorkflowMethod> toSet) {
    for (POJOWorkflowMethod parentMethod : parentMethods) {
      if (parentMethod.getType() == WorkflowMethodType.NONE) {
        Method method = parentMethod.getMethod();
        try {
          currentInterface.getMethod(method.getName(), method.getParameterTypes());
          // Don't add to result as it is redefined by current.
          // This allows overriding methods without annotation with annotated methods.
        } catch (NoSuchMethodException e) {
          // current interface doesn't have an override for this method - add it from the parent
          toSet.add(parentMethod);
        }
      } else {
        // parent interface method is explicitly annotated with one of workflow method annotations -
        // adding to the
        // result
        toSet.add(parentMethod);
      }
    }
  }

  private static void validateModifierAccess(Class<?> workflowInterface) {
    if (!Modifier.isPublic(workflowInterface.getModifiers())) {
      throw new IllegalArgumentException(
          "Interface with @WorkflowInterface annotation must be public: " + workflowInterface);
    }
  }

  /**
   * @return true if the method may be used as a workflow method, false if it can't
   * @throws IllegalArgumentException if the method is incorrectly configured (for example, a
   *     combination of {@link WorkflowMethod} and a {@code static} modifier)
   */
  private static boolean validateAndQualifiedForWorkflowMethod(POJOWorkflowMethod workflowMethod) {
    Method method = workflowMethod.getMethod();
    boolean isAnnotatedWorkflowMethod = !workflowMethod.getType().equals(WorkflowMethodType.NONE);

    if (Modifier.isStatic(method.getModifiers())) {
      if (isAnnotatedWorkflowMethod) {
        throw new IllegalArgumentException("Workflow Method can't be static: " + method);
      } else {
        return false;
      }
    }

    if (isAnnotatedWorkflowMethod) {
      // all methods explicitly marked with one of workflow method qualifiers
      return true;
    }

    if (method.isSynthetic()) {
      // if method is synthetic and not explicitly marked as a workflow method,
      // it's not qualified as a workflow method.
      // https://github.com/temporalio/sdk-java/issues/977
      // https://github.com/temporalio/sdk-java/issues/1331
      return false;
    }

    return true;
  }
}
