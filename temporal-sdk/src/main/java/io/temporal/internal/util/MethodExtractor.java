package io.temporal.internal.util;

import com.google.common.base.Defaults;
import io.temporal.common.metadata.POJOActivityInterfaceMetadata;
import io.temporal.workflow.Functions;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class MethodExtractor {

  @SuppressWarnings("unchecked")
  private static <I> Method probeMethod(Class<I> interfac, Object tag, Functions.Proc1<I> applier) {
    Box<Method> captured = new Box<>();
    I probe =
        (I)
            Proxy.newProxyInstance(
                interfac.getClassLoader(),
                new Class<?>[] {interfac},
                (proxy, method, args) -> {
                  captured.set(method);
                  return Defaults.defaultValue(method.getReturnType());
                });
    try {
      applier.apply(probe);
    } catch (Throwable ignored) {
    }
    if (captured.get() != null) {
      return captured.get();
    }
    throw new NoSuchMethodError(
        "Method probe produced no result for " + interfac.getName() + ": " + tag);
  }

  public static String activityTypeName(Class<?> interfac, Method method) {
    return POJOActivityInterfaceMetadata.newInstance(interfac)
        .getMethodMetadata(method)
        .getActivityTypeName();
  }

  // --- Proc overloads ---

  public static <I> Method extract(Class<I> interfac, Functions.Proc1<I> m) {
    return probeMethod(interfac, m, i -> m.apply(i));
  }

  public static <I, A1> Method extract(Class<I> interfac, Functions.Proc2<I, A1> m) {
    return probeMethod(interfac, m, i -> m.apply(i, null));
  }

  public static <I, A1, A2> Method extract(Class<I> interfac, Functions.Proc3<I, A1, A2> m) {
    return probeMethod(interfac, m, i -> m.apply(i, null, null));
  }

  public static <I, A1, A2, A3> Method extract(
      Class<I> interfac, Functions.Proc4<I, A1, A2, A3> m) {
    return probeMethod(interfac, m, i -> m.apply(i, null, null, null));
  }

  public static <I, A1, A2, A3, A4> Method extract(
      Class<I> interfac, Functions.Proc5<I, A1, A2, A3, A4> m) {
    return probeMethod(interfac, m, i -> m.apply(i, null, null, null, null));
  }

  public static <I, A1, A2, A3, A4, A5> Method extract(
      Class<I> interfac, Functions.Proc6<I, A1, A2, A3, A4, A5> m) {
    return probeMethod(interfac, m, i -> m.apply(i, null, null, null, null, null));
  }

  public static <I, A1, A2, A3, A4, A5, A6> Method extract(
      Class<I> interfac, Functions.Proc7<I, A1, A2, A3, A4, A5, A6> m) {
    return probeMethod(interfac, m, i -> m.apply(i, null, null, null, null, null, null));
  }

  // --- Func overloads (return Method for return-type extraction) ---

  public static <I, R> Method extract(Class<I> interfac, Functions.Func1<I, R> m) {
    return probeMethod(interfac, m, i -> m.apply(i));
  }

  public static <I, A1, R> Method extract(Class<I> interfac, Functions.Func2<I, A1, R> m) {
    return probeMethod(interfac, m, i -> m.apply(i, null));
  }

  public static <I, A1, A2, R> Method extract(Class<I> interfac, Functions.Func3<I, A1, A2, R> m) {
    return probeMethod(interfac, m, i -> m.apply(i, null, null));
  }

  public static <I, A1, A2, A3, R> Method extract(
      Class<I> interfac, Functions.Func4<I, A1, A2, A3, R> m) {
    return probeMethod(interfac, m, i -> m.apply(i, null, null, null));
  }

  public static <I, A1, A2, A3, A4, R> Method extract(
      Class<I> interfac, Functions.Func5<I, A1, A2, A3, A4, R> m) {
    return probeMethod(interfac, m, i -> m.apply(i, null, null, null, null));
  }

  public static <I, A1, A2, A3, A4, A5, R> Method extract(
      Class<I> interfac, Functions.Func6<I, A1, A2, A3, A4, A5, R> m) {
    return probeMethod(interfac, m, i -> m.apply(i, null, null, null, null, null));
  }

  public static <I, A1, A2, A3, A4, A5, A6, R> Method extract(
      Class<I> interfac, Functions.Func7<I, A1, A2, A3, A4, A5, A6, R> m) {
    return probeMethod(interfac, m, i -> m.apply(i, null, null, null, null, null, null));
  }
}
