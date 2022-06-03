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
import com.google.common.reflect.TypeToken;
import io.temporal.activity.ActivityMethod;
import io.temporal.common.MethodRetry;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Metadata of an activity implementation object.
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
public final class POJOActivityImplMetadata {

  private final List<POJOActivityInterfaceMetadata> activityInterfaces;
  private final List<POJOActivityMethodMetadata> activityMethods;

  /** Creates POJOActivityImplMetadata for an activity implementation class. */
  public static POJOActivityImplMetadata newInstance(Class<?> implementationClass) {
    return new POJOActivityImplMetadata(implementationClass);
  }

  private POJOActivityImplMetadata(Class<?> implClass) {
    if (implClass.isInterface()
        || implClass.isPrimitive()
        || implClass.isAnnotation()
        || implClass.isArray()
        || implClass.isEnum()) {
      throw new IllegalArgumentException("concrete class expected: " + implClass);
    }
    for (Method method : implClass.getMethods()) {
      if (method.getAnnotation(ActivityMethod.class) != null) {
        throw new IllegalArgumentException(
            "Found @ActivityMethod annotation on \""
                + method
                + "\" This annotation can be used only on the interface method it implements.");
      }
      if (method.getAnnotation(MethodRetry.class) != null) {
        throw new IllegalArgumentException(
            "Found @MethodRetry annotation on \""
                + method
                + "\" This annotation can be used only on the interface method it implements.");
      }
    }
    Set<Class<?>> interfaces =
        (Set<Class<?>>) TypeToken.of(implClass).getTypes().interfaces().rawTypes();
    List<POJOActivityInterfaceMetadata> activityInterfaces = new ArrayList<>();
    Map<String, POJOActivityMethodMetadata> byName = new HashMap<>();
    for (Class<?> anInterface : interfaces) {
      POJOActivityInterfaceMetadata interfaceMetadata =
          POJOActivityInterfaceMetadata.newImplementationInterface(anInterface);
      activityInterfaces.add(interfaceMetadata);
      List<POJOActivityMethodMetadata> methods = interfaceMetadata.getMethodsMetadata();
      for (POJOActivityMethodMetadata methodMetadata : methods) {
        POJOActivityMethodMetadata registeredMM =
            byName.put(methodMetadata.getActivityTypeName(), methodMetadata);
        if (registeredMM != null && !registeredMM.equals(methodMetadata)) {
          throw new IllegalArgumentException(
              "Duplicated name: \""
                  + methodMetadata.getActivityTypeName()
                  + "\" declared at \""
                  + registeredMM.getMethod()
                  + "\" registered through \""
                  + registeredMM.getInterfaceType()
                  + "\" and \""
                  + methodMetadata.getMethod()
                  + "\" registered through \""
                  + methodMetadata.getInterfaceType()
                  + "\"");
        }
      }
    }
    if (byName.isEmpty()) {
      throw new IllegalArgumentException(
          "Class doesn't implement any non empty interface annotated with @ActivityInterface: "
              + implClass.getName());
    }
    this.activityInterfaces = ImmutableList.copyOf(activityInterfaces);
    this.activityMethods = ImmutableList.copyOf(byName.values());
  }

  /** Activity interfaces implemented by the object. */
  public List<POJOActivityInterfaceMetadata> getActivityInterfaces() {
    return activityInterfaces;
  }

  /** Activity methods implemented by the object */
  public List<POJOActivityMethodMetadata> getActivityMethods() {
    return activityMethods;
  }
}
