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

package io.temporal.internal.async;

import io.temporal.internal.async.spi.MethodReferenceDisassemblyService;
import io.temporal.internal.common.JavaLambdaUtils;
import io.temporal.internal.common.kotlin.KotlinDetector;
import io.temporal.internal.sync.AsyncInternal;
import io.temporal.workflow.ActivityStub;
import io.temporal.workflow.ChildWorkflowStub;
import io.temporal.workflow.ExternalWorkflowStub;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.SerializedLambda;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

public class MethodReferenceDisassembler {
  private static final ServiceLoader<MethodReferenceDisassemblyService> loader =
      ServiceLoader.load(MethodReferenceDisassemblyService.class);

  static final Map<String, MethodReferenceDisassemblyService> services = new HashMap<>();

  static {
    loader.iterator().forEachRemaining(service -> services.put(service.getLanguageName(), service));
  }

  public static boolean isAsync(Object func) {
    return isAsyncJava(func) || isAsyncKotlin(func);
  }

  private static boolean isAsyncJava(Object func) {
    SerializedLambda lambda = JavaLambdaUtils.toSerializedLambda(func);
    Object target = JavaLambdaUtils.getTarget(lambda);
    return target instanceof ActivityStub
        || target instanceof ChildWorkflowStub
        || target instanceof ExternalWorkflowStub
        || (target instanceof AsyncInternal.AsyncMarker
            && lambda.getImplMethodKind() == MethodHandleInfo.REF_invokeInterface);
  }

  private static boolean isAsyncKotlin(Object func) {
    if (KotlinDetector.isKotlinType(func.getClass())) {
      MethodReferenceDisassemblyService methodReferenceDisassemblyService =
          services.get(MethodReferenceDisassemblyService.KOTLIN);
      if (methodReferenceDisassemblyService == null) {
        throw new IllegalStateException(
            "Kotlin method reference is used with async. "
                + "For Temporal to correctly support async invocation kotlin method references, "
                + "add io.temporal:temporal-kotlin to classpath");
      }

      Object target = methodReferenceDisassemblyService.getMethodReferenceTarget(func);

      // it looks like we actually always get AsyncMarker here. Classes like ActivityStub,
      // ChildWorkflowStub, etc are
      // always wrapped into a Proxy that implements AsyncMarker marker interface.
      return target instanceof AsyncInternal.AsyncMarker
          || target instanceof ActivityStub
          || target instanceof ChildWorkflowStub
          || target instanceof ExternalWorkflowStub;
    } else {
      return false;
    }
  }
}
