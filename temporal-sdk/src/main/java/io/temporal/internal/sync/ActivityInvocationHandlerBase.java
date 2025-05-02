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
    // Proxy the toString method so the stub can be inspected when debugging.
    try {
      if (method.equals(Object.class.getMethod("toString"))) {
        return proxyToString();
      }
    } catch (NoSuchMethodException e) {
      throw new Error("unexpected", e);
    }
    POJOActivityMethodMetadata methodMetadata = activityMetadata.getMethodMetadata(method);
    MethodRetry methodRetry = methodMetadata.getMethod().getAnnotation(MethodRetry.class);
    String activityType = methodMetadata.getActivityTypeName();
    Function<Object[], Object> function = getActivityFunc(method, methodRetry, activityType);
    return getValueOrDefault(function.apply(args), method.getReturnType());
  }

  protected abstract Function<Object[], Object> getActivityFunc(
      Method method, MethodRetry methodRetry, String activityName);

  protected abstract String proxyToString();
}
