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

import com.google.common.base.Defaults;
import io.nexusrpc.Operation;
import io.nexusrpc.ServiceDefinition;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.*;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

public class NexusServiceInvocationHandler implements InvocationHandler {
  private final NexusServiceStub stub;

  private final ServiceDefinition serviceDef;

  NexusServiceInvocationHandler(
      ServiceDefinition serviceDef,
      NexusServiceOptions options,
      WorkflowOutboundCallsInterceptor outboundCallsInterceptor,
      Functions.Proc1<String> assertReadOnly) {
    this.serviceDef = serviceDef;
    this.stub =
        new NexusServiceStubImpl(
            serviceDef.getName(), options, outboundCallsInterceptor, assertReadOnly) {};
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    if (method.getName().equals(StubMarker.GET_UNTYPED_STUB_METHOD)) {
      return stub;
    }
    Object arg = args != null ? args[0] : null;

    Operation opAnnotation = method.getAnnotation(Operation.class);
    if (opAnnotation == null) {
      throw new IllegalArgumentException("Unknown method: " + method);
    }
    String opName = !opAnnotation.name().equals("") ? opAnnotation.name() : method.getName();
    // If the method was invoked as part of a start call then we need to return a handle back
    // to the caller. The result of this method will be ignored.
    if (StartNexusCallInternal.isAsync()) {
      StartNexusCallInternal.setAsyncResult(
          this.stub.start(opName, method.getReturnType(), method.getGenericReturnType(), arg));
      return Defaults.defaultValue(method.getReturnType());
    }
    return getValueOrDefault(
        this.stub.execute(opName, method.getReturnType(), method.getGenericReturnType(), arg),
        method.getReturnType());
  }
}
