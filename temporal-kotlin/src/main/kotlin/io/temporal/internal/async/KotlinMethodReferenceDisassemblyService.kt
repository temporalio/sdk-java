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

package io.temporal.internal.async

import io.temporal.internal.async.spi.MethodReferenceDisassemblyService
import io.temporal.workflow.Functions
import kotlin.jvm.internal.CallableReference
import kotlin.jvm.internal.Lambda

class KotlinMethodReferenceDisassemblyService : MethodReferenceDisassemblyService {
  override fun getMethodReferenceTarget(methodReference: Any): Any? {
    return when (methodReference) {
      is Functions.TemporalFunctionalInterfaceMarker -> {
        unwrapTemporalFunctionalInterfaceInKotlin(methodReference)
      }
      else -> {
        unwrapIfCallableReference(methodReference)
      }
    }
  }

  private fun unwrapIfCallableReference(callableReference: Any): Any? {
    return when (callableReference) {
      /**
       * Strategy 1.1
       * We unwrap simple native Kotlin [CallableReference] (which is used for method references)
       */
      is CallableReference -> unwrapCallableReference(callableReference)
      // we end up here if lambda is passed instead of a method reference
      is Lambda<*> -> null
      // something unexpected that we don't know how to handle
      else -> null
    }
  }

  private fun unwrapCallableReference(callableReference: CallableReference): Any {
    return callableReference.boundReceiver
  }

  /**
   * Strategy 2
   * Kotlin bumped into one of [io.temporal.workflow.Async] calls that have one of our [io.temporal.workflow.Functions]
   * as a parameter and has to implement/wrap the method reference as one of our function interfaces
   */
  private fun unwrapTemporalFunctionalInterfaceInKotlin(temporalFunction: Functions.TemporalFunctionalInterfaceMarker): Any? {
    val declaredFields = temporalFunction.javaClass.declaredFields
    if (declaredFields.size != 1) {
      // something unexpected that we don't know how to handle
      return null
    }

    val proxiedField = declaredFields[0]
    proxiedField.isAccessible = true
    val proxiedValue = proxiedField[temporalFunction]
    if ("function" == proxiedField.name) {
      /**
       * Strategy 2.1
       * Kotlin 1.4 and earlier wraps Kotlin's [CallableReference]
       * into one of [io.temporal.workflow.Functions] wrappers.
       * This will be a generated class handling the Callable Reference in 'function' field.
       *
       * We also end up here in any version of Kotlin if wrapped lambda is passed and this case is handled in unwrapCallableReference
       */
      return unwrapIfCallableReference(proxiedValue)
    } else {
      /**
       * Strategy 2.2
       * Kotlin 1.5 generates one of [io.temporal.workflow.Functions] directly over the target
       * without any [CallableReference] in between, in that case our target is persisted directly
       * in the single field of the generated Function.
       */
      return proxiedValue
    }
  }

  override fun getLanguageName(): String {
    return MethodReferenceDisassemblyService.KOTLIN
  }
}
