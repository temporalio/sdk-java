package io.temporal.internal.common.kotlin;

import io.temporal.internal.common.JavaLambdaUtils;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodType;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Detects Kotlin classes and static method-reference adapters without a Kotlin dependency. */
@SuppressWarnings("unchecked")
public abstract class KotlinDetector {

  private static final Class<? extends Annotation> kotlinMetadata;

  private static final boolean kotlinReflectPresent;

  static {
    Class<?> metadata;
    ClassLoader classLoader = KotlinDetector.class.getClassLoader();
    try {
      metadata = Class.forName("kotlin.Metadata", false, classLoader);
    } catch (ClassNotFoundException ex) {
      // Kotlin API not available - no Kotlin support
      metadata = null;
    }
    kotlinMetadata = (Class<? extends Annotation>) metadata;
    kotlinReflectPresent = isPresent("kotlin.reflect.full.KClasses", classLoader);
  }

  public static boolean isPresent(String className, ClassLoader classLoader) {
    try {
      Class.forName(className, false, classLoader);
      return true;
    } catch (IllegalAccessError err) {
      throw new IllegalStateException(
          "Readability mismatch in inheritance hierarchy of class ["
              + className
              + "]: "
              + err.getMessage(),
          err);
    } catch (Throwable ex) {
      // Typically ClassNotFoundException or NoClassDefFoundError...
      return false;
    }
  }

  /** Determine whether Kotlin is present in general. */
  public static boolean isKotlinPresent() {
    return (kotlinMetadata != null);
  }

  /** Determine whether Kotlin reflection is present. */
  public static boolean isKotlinReflectPresent() {
    return kotlinReflectPresent;
  }

  /**
   * Determine whether the given {@code Class} is a Kotlin type (with Kotlin metadata present on
   * it).
   */
  public static boolean isKotlinType(Class<?> clazz) {
    return (kotlinMetadata != null && clazz.getDeclaredAnnotation(kotlinMetadata) != null);
  }

  /** Determine whether the given lambda is a Kotlin static method-reference adapter. */
  public static boolean isKotlinStaticAdapter(Object func) {
    SerializedLambda lambda = JavaLambdaUtils.toSerializedLambda(func);
    ClassLoader classLoader = func.getClass().getClassLoader();
    if (getKotlinStaticAdapterTarget(lambda, classLoader) == null) {
      return false;
    }
    try {
      Class<?> capturingClass =
          Class.forName(lambda.getCapturingClass().replace('/', '.'), false, classLoader);
      return isKotlinType(capturingClass);
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  /** Returns the target of a Kotlin static method-reference adapter, if the lambda has one. */
  public static Object getKotlinStaticAdapterTarget(
      SerializedLambda lambda, ClassLoader classLoader) {
    if (lambda == null
        || lambda.getImplMethodKind() != MethodHandleInfo.REF_invokeStatic
        || lambda.getCapturedArgCount() != 1) {
      return null;
    }
    Object target = JavaLambdaUtils.getTarget(lambda);
    String adapterName = lambda.getImplMethodName();
    int separator = adapterName.lastIndexOf('$');
    if (target == null || separator < 0 || separator == adapterName.length() - 1) {
      return null;
    }

    MethodType adapterType;
    try {
      adapterType =
          MethodType.fromMethodDescriptorString(lambda.getImplMethodSignature(), classLoader);
    } catch (IllegalArgumentException | TypeNotPresentException e) {
      return null;
    }
    Class<?>[] adapterParameters = adapterType.parameterArray();
    if (adapterParameters.length == 0 || !adapterParameters[0].isInstance(target)) {
      return null;
    }
    String methodName = adapterName.substring(separator + 1);
    Class<?>[] methodParameters =
        Arrays.copyOfRange(adapterParameters, 1, adapterParameters.length);
    for (Method method : target.getClass().getMethods()) {
      if (method.getName().equals(methodName)
          && method.getReturnType() == adapterType.returnType()
          && Arrays.equals(method.getParameterTypes(), methodParameters)) {
        return target;
      }
    }
    return null;
  }
}
