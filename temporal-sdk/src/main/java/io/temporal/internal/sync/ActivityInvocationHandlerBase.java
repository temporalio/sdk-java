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
import java.util.function.Function;

/** Dynamic implementation of a strongly typed activity interface. */
@VisibleForTesting
public abstract class ActivityInvocationHandlerBase implements InvocationHandler {
  private final POJOActivityInterfaceMetadata activityMetadata;

  protected ActivityInvocationHandlerBase(Class<?> activityInterface) {
    this.activityMetadata = POJOActivityInterfaceMetadata.newInstance(activityInterface);
  }

  @VisibleForTesting
  @SuppressWarnings("unchecked")
  public static <T> T newProxy(Class<T> activityInterface, InvocationHandler invocationHandler) {
    return (T)
        Proxy.newProxyInstance(
            activityInterface.getClassLoader(),
            new Class<?>[] {activityInterface, AsyncMarker.class},
            invocationHandler);
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) {
    POJOActivityMethodMetadata methodMetadata = activityMetadata.getMethodMetadata(method);
    MethodRetry methodRetry = methodMetadata.getMethod().getAnnotation(MethodRetry.class);
    String activityType = methodMetadata.getActivityTypeName();
    Function<Object[], Object> function = getActivityFunc(method, methodRetry, activityType);
    return getValueOrDefault(function.apply(args), method.getReturnType());
  }

  protected abstract Function<Object[], Object> getActivityFunc(
      Method method, MethodRetry methodRetry, String activityName);
}
