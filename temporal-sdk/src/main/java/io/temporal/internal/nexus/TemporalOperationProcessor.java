package io.temporal.internal.nexus;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Primitives;
import io.nexusrpc.OperationDefinition;
import io.nexusrpc.ServiceDefinition;
import io.nexusrpc.handler.MethodExtension;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImplInstance;
import io.temporal.nexus.TemporalNexusClient;
import io.temporal.nexus.TemporalOperation;
import io.temporal.nexus.TemporalOperationHandler;
import io.temporal.nexus.TemporalOperationResult;
import io.temporal.nexus.TemporalOperationStartContext;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Entry point for registering a Nexus service instance whose class may contain {@link
 * TemporalOperation}-annotated methods. Delegates to {@link
 * ServiceImplInstance#fromInstance(Object, java.util.List)} with a single {@link MethodExtension}
 * that recognizes {@link TemporalOperation} alongside the built-in {@link OperationImpl}.
 */
public final class TemporalOperationProcessor {

  private static final ImmutableList<MethodExtension> EXTENSIONS =
      ImmutableList.of(new TemporalOperationExtension());

  private TemporalOperationProcessor() {}

  public static ServiceImplInstance process(Object instance) {
    return ServiceImplInstance.fromInstance(instance, EXTENSIONS);
  }

  /** Recognizes {@link TemporalOperation}-annotated methods during nexusrpc service scanning. */
  private static final class TemporalOperationExtension implements MethodExtension {
    @Override
    public Result extract(Object instance, Method method, ServiceDefinition serviceDefinition) {
      if (method.getDeclaredAnnotation(TemporalOperation.class) == null) {
        return null;
      }
      if (method.isAnnotationPresent(OperationImpl.class)) {
        throw new IllegalArgumentException(
            "@TemporalOperation and @OperationImpl cannot be combined on method "
                + method.getName());
      }

      validateSignature(method);

      OperationDefinition operationDefinition =
          serviceDefinition.getOperations().values().stream()
              .filter(o -> method.getName().equals(o.getMethodName()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "No matching @Operation on service "
                              + serviceDefinition.getName()
                              + " for @TemporalOperation method "
                              + method.getName()));

      validateTypes(method, operationDefinition);

      MethodHandle handle;
      try {
        handle = MethodHandles.lookup().unreflect(method).bindTo(instance);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(
            "Failed to obtain method handle for @TemporalOperation method " + method.getName(), e);
      }

      TemporalOperationHandler.StartHandler<Object, Object> startHandler =
          (ctx, client, input) -> invokeStartHandler(handle, ctx, client, input);

      return new Result(
          operationDefinition.getName(),
          new TemporalOperationHandler<Object, Object>(startHandler) {});
    }
  }

  private static void validateSignature(Method method) {
    if (!Modifier.isPublic(method.getModifiers())) {
      throw new IllegalArgumentException(
          "@TemporalOperation method " + method.getName() + " must be public");
    }
    if (Modifier.isStatic(method.getModifiers())) {
      throw new IllegalArgumentException(
          "@TemporalOperation method " + method.getName() + " must not be static");
    }
    Class<?>[] paramTypes = method.getParameterTypes();
    if (paramTypes.length != 3
        || !TemporalOperationStartContext.class.equals(paramTypes[0])
        || !TemporalNexusClient.class.equals(paramTypes[1])) {
      throw new IllegalArgumentException(
          "@TemporalOperation method "
              + method.getName()
              + " must accept (TemporalOperationStartContext, TemporalNexusClient, I); got "
              + describeSignature(method));
    }
    if (!TemporalOperationResult.class.equals(method.getReturnType())) {
      throw new IllegalArgumentException(
          "@TemporalOperation method "
              + method.getName()
              + " must return TemporalOperationResult<?>; got "
              + method.getGenericReturnType().getTypeName()
              + ". Use @OperationImpl for custom handler shapes.");
    }
  }

  private static void validateTypes(Method method, OperationDefinition operationDefinition) {
    Type expectedInputType = operationDefinition.getInputType();
    Type declaredInputType = method.getGenericParameterTypes()[2];
    if (!typesMatch(declaredInputType, expectedInputType)) {
      throw new IllegalArgumentException(
          "@TemporalOperation method "
              + method.getName()
              + " input type mismatch: expected "
              + expectedInputType.getTypeName()
              + " but got "
              + declaredInputType.getTypeName());
    }
    Type returnType = method.getGenericReturnType();
    if (!(returnType instanceof ParameterizedType)) {
      throw new IllegalArgumentException(
          "@TemporalOperation method "
              + method.getName()
              + " must use parameterized TemporalOperationResult<R>, not the raw type.");
    }
    Type resultTypeArg = ((ParameterizedType) returnType).getActualTypeArguments()[0];
    if (!typesMatch(resultTypeArg, operationDefinition.getOutputType())) {
      throw new IllegalArgumentException(
          "@TemporalOperation method "
              + method.getName()
              + " output type mismatch: expected "
              + operationDefinition.getOutputType().getTypeName()
              + " but got "
              + resultTypeArg.getTypeName());
    }
  }

  // Package-private for testing.
  @SuppressWarnings("unchecked")
  static TemporalOperationResult<Object> invokeStartHandler(
      MethodHandle handle,
      TemporalOperationStartContext ctx,
      TemporalNexusClient client,
      Object input) {
    try {
      return (TemporalOperationResult<Object>) handle.invoke(ctx, client, input);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new RuntimeException("@TemporalOperation method threw checked exception", t);
    }
  }

  private static boolean typesMatch(Type declared, Type expected) {
    if (declared.equals(expected)) {
      return true;
    }
    if (declared instanceof Class && expected instanceof Class) {
      return Primitives.wrap((Class<?>) declared).equals(Primitives.wrap((Class<?>) expected));
    }
    return false;
  }

  private static String describeSignature(Method method) {
    return Arrays.stream(method.getParameterTypes())
        .map(Class::getSimpleName)
        .collect(Collectors.joining(", ", "(", ")"));
  }
}
