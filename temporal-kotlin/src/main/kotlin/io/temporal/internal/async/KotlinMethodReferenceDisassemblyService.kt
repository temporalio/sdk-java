
package io.temporal.internal.async

import io.temporal.internal.async.spi.MethodReferenceDisassemblyService
import io.temporal.internal.common.JavaLambdaUtils
import io.temporal.internal.common.kotlin.KotlinDetector
import io.temporal.workflow.Functions
import kotlin.jvm.internal.CallableReference
import kotlin.jvm.internal.Lambda

class KotlinMethodReferenceDisassemblyService : MethodReferenceDisassemblyService {
  override fun getMethodReferenceTarget(methodReference: Any): Any? {
    val callableTarget = unwrapIfCallableReference(methodReference)
    if (callableTarget != null) {
      return callableTarget
    }
    if (methodReference is Functions.TemporalFunctionalInterfaceMarker) {
      val wrappedTarget = unwrapTemporalFunctionalInterfaceInKotlin(methodReference)
      if (wrappedTarget != null) {
        return wrappedTarget
      }
    }
    return unwrapKotlinStaticAdapter(methodReference)
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
    } else if (KotlinDetector.isKotlinType(temporalFunction.javaClass)) {
      /**
       * Strategy 2.2
       * Kotlin 1.5 generates one of [io.temporal.workflow.Functions] directly over the target
       * without any [CallableReference] in between, in that case our target is persisted directly
       * in the single field of the generated Function.
       */
      return proxiedValue
    }
    return null
  }

  /**
   * Strategy 3
   * Kotlin 2.4 uses a serialized lambda with a static adapter for a method reference.
   * A lambda that captures the same target also has one field, so verify the adapter's method.
   */
  private fun unwrapKotlinStaticAdapter(methodReference: Any): Any? {
    if (System.getProperty("temporal.kotlin.disableStaticAdapterUnwrapping") != null) {
      return null
    }
    if (methodReference !is Functions.TemporalFunctionalInterfaceMarker) {
      return null
    }
    val serializedLambda = JavaLambdaUtils.toSerializedLambda(methodReference) ?: return null
    return KotlinDetector.getKotlinStaticAdapterTarget(serializedLambda, methodReference.javaClass.classLoader)
  }

  override fun getLanguageName(): String {
    return MethodReferenceDisassemblyService.KOTLIN
  }
}
