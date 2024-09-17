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

import com.google.common.collect.ImmutableList;
import io.temporal.common.Experimental;
import io.temporal.internal.common.env.ReflectionUtils;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

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
public final class POJOWorkflowImplMetadata {

  private static class EqualsByNameType {
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

  private final List<POJOWorkflowInterfaceMetadata> workflowInterfaces;
  private final List<POJOWorkflowMethodMetadata> workflowMethods;
  private final List<POJOWorkflowMethodMetadata> signalMethods;
  private final List<POJOWorkflowMethodMetadata> queryMethods;
  private final List<POJOWorkflowMethodMetadata> updateMethods;
  private final List<POJOWorkflowMethodMetadata> updateValidatorMethods;
  private final Constructor<?> workflowInit;

  /**
   * Create POJOWorkflowImplMetadata for a workflow implementation class. The object must implement
   * at least one workflow method.
   */
  public static POJOWorkflowImplMetadata newInstance(Class<?> implClass) {
    return new POJOWorkflowImplMetadata(implClass, false);
  }

  /**
   * Create POJOWorkflowImplMetadata for a workflow implementation class. The class may not
   * implement any workflow method. This is to be used for classes that implement only query and
   * signal methods.
   */
  public static POJOWorkflowImplMetadata newListenerInstance(Class<?> implClass) {
    return new POJOWorkflowImplMetadata(implClass, true);
  }

  private POJOWorkflowImplMetadata(Class<?> implClass, boolean listener) {
    if (implClass.isInterface()
        || implClass.isPrimitive()
        || implClass.isAnnotation()
        || implClass.isArray()
        || implClass.isEnum()) {
      throw new IllegalArgumentException("concrete class expected: " + implClass);
    }

    List<POJOWorkflowInterfaceMetadata> workflowInterfaces = new ArrayList<>();
    Map<String, POJOWorkflowMethodMetadata> workflowMethods = new HashMap<>();
    Map<String, POJOWorkflowMethodMetadata> queryMethods = new HashMap<>();
    Map<String, POJOWorkflowMethodMetadata> signalMethods = new HashMap<>();
    Map<String, POJOWorkflowMethodMetadata> updateMethods = new HashMap<>();
    Map<String, POJOWorkflowMethodMetadata> updateValidatorMethods = new HashMap<>();
    Map<EqualsByNameType, POJOWorkflowMethodMetadata> byNameType = new HashMap<>();

    // Getting all the top level interfaces instead of the direct ones that Class.getInterfaces()
    // returns
    Set<Class<?>> interfaces = POJOReflectionUtils.getTopLevelInterfaces(implClass);
    for (Class<?> anInterface : interfaces) {
      POJOWorkflowInterfaceMetadata interfaceMetadata =
          POJOWorkflowInterfaceMetadata.newImplementationInstance(anInterface, listener);
      List<POJOWorkflowMethodMetadata> methods = interfaceMetadata.getMethodsMetadata();
      if (!methods.isEmpty()) {
        workflowInterfaces.add(interfaceMetadata);
      }
      for (POJOWorkflowMethodMetadata methodMetadata : methods) {
        EqualsByNameType key =
            new EqualsByNameType(methodMetadata.getName(), methodMetadata.getType());
        POJOWorkflowMethodMetadata registeredMM = byNameType.put(key, methodMetadata);
        if (registeredMM != null
            && !registeredMM.getWorkflowMethod().equals(methodMetadata.getWorkflowMethod())) {
          throw new IllegalArgumentException(
              "Duplicated name of "
                  + methodMetadata.getType()
                  + ": \""
                  + methodMetadata.getName()
                  + "\" declared at \""
                  + registeredMM.getWorkflowMethod()
                  + "\" and \""
                  + methodMetadata.getWorkflowMethod()
                  + "\"");
        }
        switch (methodMetadata.getType()) {
          case WORKFLOW:
            workflowMethods.put(methodMetadata.getName(), methodMetadata);
            break;
          case SIGNAL:
            signalMethods.put(methodMetadata.getName(), methodMetadata);
            break;
          case QUERY:
            queryMethods.put(methodMetadata.getName(), methodMetadata);
            break;
          case UPDATE:
            updateMethods.put(methodMetadata.getName(), methodMetadata);
            break;
          case UPDATE_VALIDATOR:
            updateValidatorMethods.put(methodMetadata.getName(), methodMetadata);
            break;
        }
      }
    }
    if (byNameType.isEmpty() && !listener) {
      throw new IllegalArgumentException(
          "Class doesn't implement any non empty public interface annotated with @WorkflowInterface: "
              + implClass.getName());
    }
    this.workflowInterfaces = ImmutableList.copyOf(workflowInterfaces);
    this.workflowMethods = ImmutableList.copyOf(workflowMethods.values());
    this.signalMethods = ImmutableList.copyOf(signalMethods.values());
    this.queryMethods = ImmutableList.copyOf(queryMethods.values());
    this.updateMethods = ImmutableList.copyOf(updateMethods.values());
    this.updateValidatorMethods = ImmutableList.copyOf(updateValidatorMethods.values());
    if (!listener) {
      this.workflowInit =
          ReflectionUtils.getConstructor(
                  implClass,
                  this.workflowMethods.stream()
                      .map(POJOWorkflowMethodMetadata::getWorkflowMethod)
                      .collect(Collectors.toList()))
              .orElse(null);
    } else {
      this.workflowInit = null;
    }
  }

  /** List of workflow interfaces an object implements. */
  public List<POJOWorkflowInterfaceMetadata> getWorkflowInterfaces() {
    return workflowInterfaces;
  }

  /** List of workflow methods an object implements across all the workflow interfaces. */
  public List<POJOWorkflowMethodMetadata> getWorkflowMethods() {
    return workflowMethods;
  }

  /** List of signal methods an object implements across all the workflow interfaces. */
  public List<POJOWorkflowMethodMetadata> getSignalMethods() {
    return signalMethods;
  }

  /** List of query methods an object implements across all the workflow interfaces. */
  public List<POJOWorkflowMethodMetadata> getQueryMethods() {
    return queryMethods;
  }

  /** List of update methods an object implements across all the workflow interfaces. */
  @Experimental
  public List<POJOWorkflowMethodMetadata> getUpdateMethods() {
    return updateMethods;
  }

  /** List of update validator methods an object implements across all the workflow interfaces. */
  @Experimental
  public List<POJOWorkflowMethodMetadata> getUpdateValidatorMethods() {
    return updateValidatorMethods;
  }

  @Experimental
  public @Nullable Constructor<?> getWorkflowInit() {
    return workflowInit;
  }
}
