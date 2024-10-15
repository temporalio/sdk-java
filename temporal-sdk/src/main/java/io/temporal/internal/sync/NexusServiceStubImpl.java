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

import com.google.common.base.Defaults;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.failure.TemporalFailure;
import io.temporal.workflow.*;
import java.lang.reflect.Type;
import java.util.Collections;

public class NexusServiceStubImpl implements NexusServiceStub {
  final String name;
  final NexusServiceOptions options;
  final WorkflowOutboundCallsInterceptor outboundCallsInterceptor;
  final Functions.Proc1<String> assertReadOnly;

  public NexusServiceStubImpl(
      String name,
      NexusServiceOptions options,
      WorkflowOutboundCallsInterceptor outboundCallsInterceptor,
      Functions.Proc1<String> assertReadOnly) {
    this.name = name;
    this.options = options;
    this.outboundCallsInterceptor = outboundCallsInterceptor;
    this.assertReadOnly = assertReadOnly;
  }

  @Override
  public <R> R execute(String operationName, Class<R> resultClass, Object arg) {
    return execute(operationName, resultClass, resultClass, arg);
  }

  @Override
  public <R> R execute(String operationName, Class<R> resultClass, Type resultType, Object arg) {
    assertReadOnly.apply("execute nexus operation");
    Promise<R> result = executeAsync(operationName, resultClass, resultType, arg);
    if (AsyncInternal.isAsync()) {
      AsyncInternal.setAsyncResult(result);
      return Defaults.defaultValue(resultClass);
    }
    try {
      return result.get();
    } catch (TemporalFailure e) {
      // Reset stack to the current one. Otherwise, it is very confusing to see a stack of
      // an event handling method.
      e.setStackTrace(Thread.currentThread().getStackTrace());
      throw e;
    }
  }

  @Override
  public <R> Promise<R> executeAsync(String operationName, Class<R> resultClass, Object arg) {
    return executeAsync(operationName, resultClass, resultClass, arg);
  }

  @Override
  public <R> Promise<R> executeAsync(
      String operationName, Class<R> resultClass, Type resultType, Object arg) {
    assertReadOnly.apply("execute nexus operation");
    NexusOperationOptions mergedOptions =
        NexusOperationOptions.newBuilder(options.getOperationOptions())
            .mergeNexusOperationOptions(options.getOperationMethodOptions().get(operationName))
            .build();
    WorkflowOutboundCallsInterceptor.ExecuteNexusOperationOutput<R> result =
        outboundCallsInterceptor.executeNexusOperation(
            new WorkflowOutboundCallsInterceptor.ExecuteNexusOperationInput<>(
                options.getEndpoint(),
                name,
                operationName,
                resultClass,
                resultType,
                arg,
                mergedOptions,
                Collections.emptyMap()));
    return result.getResult();
  }

  @Override
  public <R> NexusOperationHandle<R> start(String operationName, Class<R> resultClass, Object arg) {
    return start(operationName, resultClass, resultClass, arg);
  }

  @Override
  public <R> NexusOperationHandle<R> start(
      String operationName, Class<R> resultClass, Type resultType, Object arg) {
    assertReadOnly.apply("schedule nexus operation");
    NexusOperationOptions mergedOptions =
        NexusOperationOptions.newBuilder(options.getOperationOptions())
            .mergeNexusOperationOptions(options.getOperationMethodOptions().get(operationName))
            .build();
    WorkflowOutboundCallsInterceptor.ExecuteNexusOperationOutput<R> result =
        outboundCallsInterceptor.executeNexusOperation(
            new WorkflowOutboundCallsInterceptor.ExecuteNexusOperationInput<>(
                options.getEndpoint(),
                name,
                operationName,
                resultClass,
                resultType,
                arg,
                mergedOptions,
                Collections.emptyMap()));
    return new NexusOperationHandleImpl(result.getOperationExecution(), result.getResult());
  }
}
