package io.temporal.internal.util;

import com.google.common.base.Defaults;
import io.temporal.common.metadata.POJOActivityInterfaceMetadata;
import io.temporal.workflow.Functions;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class MethodExtractor {
  @SuppressWarnings("unchecked")
  private static <I> String extractWithApplier(
      Class<I> interfac, Object originalMethod, Functions.Proc1<I> applier) {
    Method[] result = {null};

    I probe =
        (I)
            Proxy.newProxyInstance(
                interfac.getClassLoader(),
                new Class<?>[] {interfac},
                (proxy, method, args) -> {
                  result[0] = method;
                  return Defaults.defaultValue(method.getReturnType());
                });

    applier.apply(probe);

    if (result[0] != null) {
      POJOActivityInterfaceMetadata metadata = POJOActivityInterfaceMetadata.newInstance(interfac);
      return metadata.getMethodMetadata(result[0]).getActivityTypeName();
    }

    throw new RuntimeException(
        "Method probe failed: interface " + interfac + " method " + originalMethod);
  }

  public static <I, A1> String extract(Class<I> interfac, Functions.Proc2<I, A1> method) {
    return extractWithApplier(interfac, method, (i) -> method.apply(i, null));
  }
}
