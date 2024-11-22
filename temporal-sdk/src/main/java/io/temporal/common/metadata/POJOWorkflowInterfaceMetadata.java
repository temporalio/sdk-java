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

import io.temporal.workflow.*;
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
   * @deprecated use {@link #newInstance(Class, boolean)} with {@code
   *     validateWorkflowAnnotation==false}.
   */
  @Deprecated
  public static POJOWorkflowInterfaceMetadata newInstanceSkipWorkflowAnnotationCheck(
      Class<?> anInterface) {
    return newInstance(anInterface, false);
  }

  /**
   * Use this method to create a metadata for an {@link WorkflowInterface} annotated interface.
   *
   * <p>Requires
   *
   * <ul>
   *   <li>{@code anInterface} to be annotated with {@link WorkflowInterface}
   *   <li>All methods of {@code anInterface} to be annotated with {@link WorkflowMethod}, {@link
   *       QueryMethod}, {@link UpdateMethod}, {@link UpdateValidatorMethod} or {@link SignalMethod}
   * </ul>
   *
   * @param anInterface interface to create metadata for
   * @return metadata for the interface
   * @throws IllegalArgumentException if {@code anInterface} fails validation
   */
  public static POJOWorkflowInterfaceMetadata newInstance(Class<?> anInterface) {
    return newInstanceInternal(anInterface, true, true);
  }

  /**
   * Use this method to create a metadata for an {@link WorkflowInterface} annotated interface or
   * some of its partial base classes that me have some workflow methods but not be a full annotated
   * {@link WorkflowInterface}.
   *
   * <p>Requires
   *
   * <ul>
   *   <li>All methods of {@code anInterface} to be annotated with {@link WorkflowMethod}, {@link
   *       QueryMethod} or {@link SignalMethod}
   * </ul>
   *
   * @param anInterface interface to create metadata for
   * @param validateWorkflowAnnotation if false, allows {@code anInterface} to not be annotated with
   *     {@link WorkflowInterface}. This to support invoking workflow signal and query methods
   *     through a base interface without such an annotation.
   * @return metadata for the interface
   * @throws IllegalArgumentException if {@code anInterface} fails validation
   */
  public static POJOWorkflowInterfaceMetadata newInstance(
      Class<?> anInterface, boolean validateWorkflowAnnotation) {
    return newInstanceInternal(anInterface, validateWorkflowAnnotation, true);
  }

  /**
   * The core difference from main stub methods {@link #newInstance(Class)} and {@link
   * #newInstance(Class, boolean)} is that the interfaces passing here can be not annotated with
   * {@link WorkflowInterface} at all and even not having {@link WorkflowInterface} as a parent. It
   * also can have all kinds of additional methods that are not annotated with {@link
   * WorkflowMethod}, {@link QueryMethod}, {@link UpdateMethod}, {@link UpdateValidatorMethod} or
   * {@link SignalMethod}. Such unannotated methods or methods that are not part of some {@link
   * WorkflowInterface} will be ignored.
   *
   * @param anInterface interface to create metadata for
   * @param forceProcessWorkflowMethods if true, methods of the {@code anInterface} that are
   *     annotated with {@link WorkflowMethod}, {@link QueryMethod}, {@link UpdateMethod}, {@link
   *     UpdateValidatorMethod} or {@link SignalMethod} are processed like {@code current} is a
   *     workflow interface even if it is not annotated with {@link WorkflowInterface} itself. For
   *     example, this is useful when we have a query-only interface to register as a listener or
   *     call as a stub.
   * @return metadata for the interface
   */
  static POJOWorkflowInterfaceMetadata newImplementationInstance(
      Class<?> anInterface, boolean forceProcessWorkflowMethods) {
    return newInstanceInternal(anInterface, false, forceProcessWorkflowMethods);
  }

  /**
   * Ensures that the interface that the stub is created with is a {@link WorkflowInterface} with
   * the right structure.
   *
   * @param anInterface interface class
   * @param validateWorkflowAnnotation check if the interface has a {@link WorkflowInterface}
   *     annotation with methods
   * @param forceProcessWorkflowMethods if true, methods of the {@code anInterface} that are
   *     annotated with {@link WorkflowMethod}, {@link QueryMethod}, {@link UpdateMethod}, {@link
   *     UpdateValidatorMethod} or {@link SignalMethod} are processed like {@code current} is a
   *     workflow interface even if it is not annotated with {@link WorkflowInterface} itself. For
   *     example, this is useful when we have a query-only interface to register as a listener or
   *     call as a stub.
   */
  private static POJOWorkflowInterfaceMetadata newInstanceInternal(
      Class<?> anInterface,
      boolean validateWorkflowAnnotation,
      boolean forceProcessWorkflowMethods) {
    if (!anInterface.isInterface()) {
      throw new IllegalArgumentException("Not an interface: " + anInterface);
    }
    if (validateWorkflowAnnotation) {
      WorkflowInterface annotation = anInterface.getAnnotation(WorkflowInterface.class);
      if (annotation == null) {
        throw new IllegalArgumentException(
            "Missing required @WorkflowInterface annotation: " + anInterface);
      }
      validateModifierAccess(anInterface);
    }
    POJOWorkflowInterfaceMetadata result =
        new POJOWorkflowInterfaceMetadata(anInterface, forceProcessWorkflowMethods);
    if (result.methods.isEmpty()) {
      if (validateWorkflowAnnotation) {
        throw new IllegalArgumentException(
            "Interface doesn't contain any methods: " + anInterface.getName());
      }
    }
    // Validate that all @UpdateValidatorMethod methods have corresponding @UpdateMethod
    Map<String, POJOWorkflowMethodMetadata> updateMethods = new HashMap<>();
    for (POJOWorkflowMethodMetadata methodMetadata : result.methods.values()) {
      if (methodMetadata.getType() == WorkflowMethodType.UPDATE) {
        updateMethods.put(methodMetadata.getName(), methodMetadata);
      }
    }

    for (POJOWorkflowMethodMetadata methodMetadata : result.methods.values()) {
      if (methodMetadata.getType() == WorkflowMethodType.UPDATE_VALIDATOR) {
        UpdateValidatorMethod validator =
            methodMetadata.getWorkflowMethod().getAnnotation(UpdateValidatorMethod.class);
        POJOWorkflowMethodMetadata update = updateMethods.get(validator.updateName());
        if (update == null) {
          throw new IllegalArgumentException(
              "Missing @UpdateMethod with name \""
                  + validator.updateName()
                  + "\" for @UpdateValidatorMethod \""
                  + methodMetadata.getWorkflowMethod().getName()
                  + "\"");
        }
        if (!Arrays.equals(
            update.getWorkflowMethod().getGenericParameterTypes(),
            methodMetadata.getWorkflowMethod().getGenericParameterTypes())) {
          throw new IllegalArgumentException(
              "Update method \""
                  + update.getWorkflowMethod().getName()
                  + "\" and Validator method \""
                  + methodMetadata.getWorkflowMethod().getName()
                  + "\" do not have the same parameters");
        }
      }
    }
    return result;
  }

  /**
   * @param forceProcessWorkflowMethods if true, methods of the {@code anInterface} that are
   *     annotated with {@link WorkflowMethod}, {@link QueryMethod} or {@link SignalMethod} are
   *     processed like {@code current} is a workflow interface even if it is not annotated with
   *     {@link WorkflowInterface} itself. For example, this is useful when we have a query-only
   *     interface to register as a listener or call as a stub.
   */
  private POJOWorkflowInterfaceMetadata(Class<?> anInterface, boolean forceProcessWorkflowMethods) {
    this.interfaceClass = anInterface;
    Map<EqualsByName, Method> dedupeMap = new HashMap<>();
    getWorkflowInterfaceMethods(anInterface, forceProcessWorkflowMethods, dedupeMap);
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
   * @param current interface to process
   * @param forceProcessWorkflowMethods if true, methods of the {@code current} that are annotated
   *     with {@link WorkflowMethod}, {@link QueryMethod} or {@link SignalMethod} are processed like
   *     {@code current} is a workflow interface even if it is not annotated with {@link
   *     WorkflowInterface} itself. For example, this is useful when we have a query-only interface
   *     to register as a listener or call as a stub.
   * @return methods which are not handled as workflow methods
   */
  private Set<POJOWorkflowMethod> getWorkflowInterfaceMethods(
      Class<?> current, boolean forceProcessWorkflowMethods, Map<EqualsByName, Method> dedupeMap) {
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

    if (isCurrentAWorkflowInterface || forceProcessWorkflowMethods) {
      for (POJOWorkflowMethod workflowMethod : result) {
        if (workflowMethod.getType() != WorkflowMethodType.NONE) {
          dedupeAndAdd(workflowMethod, dedupeMap);

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
        } else {
          // If it's not a workflow interface, don't enforce that all methods have to be workflow
          // methods.
          // Just ignore the methods that are not workflow methods.
          // It allows workflow implementation to implement interfaces that are not workflow
          // interfaces and don't error out here.
          if (isCurrentAWorkflowInterface) {
            // Workflow methods are different from activity methods.
            // Method in a hierarchy of activity interfaces that is not annotated is an activity
            // interface.
            // Method in a hierarchy of workflow interfaces that is not annotated is a mistake.
            throw new IllegalArgumentException(
                "Missing @WorkflowMethod, @SignalMethod or @QueryMethod annotation on "
                    + workflowMethod.getMethod());
          }
        }
      }
      // the current interface is a WorkflowInterface, or forced to processed like one,
      // so we process the collected methods and there is nothing to pass down
      return Collections.emptySet();
    } else {
      // Not annotated and not a recursion root.
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
