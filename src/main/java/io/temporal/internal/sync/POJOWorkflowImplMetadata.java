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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
class POJOWorkflowImplMetadata {

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

  private final Map<String, POJOWorkflowMethodMetadata> workflowMethods = new HashMap<>();
  private final Map<String, POJOWorkflowMethodMetadata> signalMethods = new HashMap<>();
  private final Map<String, POJOWorkflowMethodMetadata> queryMethods = new HashMap<>();

  public static POJOWorkflowImplMetadata newInstance(Class<?> implClass) {
    return new POJOWorkflowImplMetadata(implClass, false);
  }

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
    Map<EqualsByNameType, POJOWorkflowMethodMetadata> byNameType = new HashMap<>();
    Class<?>[] interfaces = implClass.getInterfaces();
    for (int i = 0; i < interfaces.length; i++) {
      Class<?> anInterface = interfaces[i];
      POJOWorkflowInterfaceMetadata interfaceMetadata;
      if (listener) {
        interfaceMetadata =
            POJOWorkflowInterfaceMetadata.newInstanceSkipWorkflowAnnotationCheck(anInterface);
      } else {
        interfaceMetadata = POJOWorkflowInterfaceMetadata.newImplementationInterface(anInterface);
      }
      List<POJOWorkflowMethodMetadata> methods = interfaceMetadata.getMethodsMetadata();
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
        }
      }
    }
    if (byNameType.isEmpty() && !listener) {
      throw new IllegalArgumentException(
          "Class doesn't implement any non empty interface annotated with @WorkflowInterface: "
              + implClass.getName());
    }
  }

  public Set<String> getWorkflowTypes() {
    return workflowMethods.keySet();
  }

  public POJOWorkflowMethodMetadata getWorkflowMethodMetadata(String workflowType) {
    POJOWorkflowMethodMetadata result = workflowMethods.get(workflowType);
    if (result == null) {
      throw new IllegalArgumentException("Unknown workflow type: " + workflowType);
    }
    return result;
  }

  public Set<String> getSignalTypes() {
    return signalMethods.keySet();
  }

  public POJOWorkflowMethodMetadata getSignalMethodMetadata(String signalType) {
    POJOWorkflowMethodMetadata result = signalMethods.get(signalType);
    if (result == null) {
      throw new IllegalArgumentException(
          "Unknown signal type \""
              + signalType
              + "\", registeredSignals are: \""
              + String.join(", ", signalMethods.keySet())
              + "\"");
    }
    return result;
  }

  public Set<String> getQueryTypes() {
    return queryMethods.keySet();
  }

  public POJOWorkflowMethodMetadata getQueryMethodMetadata(String queryType) {
    POJOWorkflowMethodMetadata result = queryMethods.get(queryType);
    if (result == null) {
      throw new IllegalArgumentException("Unknown query type: " + queryType);
    }
    return result;
  }
}
