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

import static io.temporal.internal.common.InternalUtils.getAnnotatedInterfaceMethodsFromInterface;
import static io.temporal.internal.common.InternalUtils.getValueOrDefault;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.common.MethodRetry;
import io.temporal.internal.common.InternalUtils;
import io.temporal.internal.sync.AsyncInternal.AsyncMarker;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Dynamic implementation of a strongly typed activity interface. */
abstract class ActivityInvocationHandlerBase implements InvocationHandler {

  private final Map<Method, Function<Object[], Object>> methodFunctions = new HashMap<>();

  @SuppressWarnings("unchecked")
  static <T> T newProxy(Class<T> activityInterface, InvocationHandler invocationHandler) {
    return (T)
        Proxy.newProxyInstance(
            WorkflowInternal.class.getClassLoader(),
            new Class<?>[] {activityInterface, AsyncMarker.class},
            invocationHandler);
  }

  protected void init(Class<?> activityInterface) {
    Set<InternalUtils.MethodInterfacePair> activityMethods =
        getAnnotatedInterfaceMethodsFromInterface(activityInterface, ActivityInterface.class);
    if (activityMethods.isEmpty()) {
      throw new IllegalArgumentException(
          "Class doesn't implement any non empty interface annotated with @ActivityInterface: "
              + activityInterface.getName());
    }
    for (InternalUtils.MethodInterfacePair pair : activityMethods) {
      Method method = pair.getMethod();
      ActivityMethod activityMethod = method.getAnnotation(ActivityMethod.class);
      String activityType;
      if (activityMethod != null && !activityMethod.name().isEmpty()) {
        activityType = activityMethod.name();
      } else {
        activityType = InternalUtils.getSimpleName(pair);
      }

      MethodRetry methodRetry = method.getAnnotation(MethodRetry.class);
      Function<Object[], Object> function = getActivityFunc(method, methodRetry, activityType);
      methodFunctions.put(method, function);
    }
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) {
    Function<Object[], Object> function = methodFunctions.get(method);
    if (function == null) {
      throw new IllegalArgumentException("Unexpected method: " + method);
    }
    return getValueOrDefault(function.apply(args), method.getReturnType());
  }

  protected abstract Function<Object[], Object> getActivityFunc(
      Method method, MethodRetry methodRetry, String activityName);
}
