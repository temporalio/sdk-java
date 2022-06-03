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

package io.temporal.internal.sync;

import static io.temporal.internal.common.InternalUtils.getValueOrDefault;

import com.google.common.annotations.VisibleForTesting;
import io.temporal.common.MethodRetry;
import io.temporal.common.metadata.POJOActivityInterfaceMetadata;
import io.temporal.common.metadata.POJOActivityMethodMetadata;
import io.temporal.internal.sync.AsyncInternal.AsyncMarker;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Dynamic implementation of a strongly typed activity interface. */
@VisibleForTesting
public abstract class ActivityInvocationHandlerBase implements InvocationHandler {

  private final Map<Method, Function<Object[], Object>> methodFunctions = new HashMap<>();

  @VisibleForTesting
  @SuppressWarnings("unchecked")
  public static <T> T newProxy(Class<T> activityInterface, InvocationHandler invocationHandler) {
    return (T)
        Proxy.newProxyInstance(
            activityInterface.getClassLoader(),
            new Class<?>[] {activityInterface, AsyncMarker.class},
            invocationHandler);
  }

  protected void init(Class<?> activityInterface) {
    POJOActivityInterfaceMetadata activityMetadata =
        POJOActivityInterfaceMetadata.newInstance(activityInterface);
    for (POJOActivityMethodMetadata methodMetadata : activityMetadata.getMethodsMetadata()) {
      Method method = methodMetadata.getMethod();
      String activityType = methodMetadata.getActivityTypeName();
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
