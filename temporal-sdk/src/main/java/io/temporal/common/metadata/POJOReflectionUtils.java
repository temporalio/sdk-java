package io.temporal.common.metadata;

import java.util.*;

final class POJOReflectionUtils {
  private POJOReflectionUtils() {}

  /**
   * Return all interfaces that the given class implements as a Set, including ones implemented by
   * superclasses. This method doesn't go through interface inheritance hierarchy, meaning {@code
   * clazz} or it's superclasses need to directly implement an interface for this method to return
   * it.
   *
   * <p>If the class itself is an interface, it gets returned as sole interface.
   *
   * @param clazz the class to analyze for interfaces
   * @return all interfaces that the given object implements as a Set
   */
  public static Set<Class<?>> getTopLevelInterfaces(Class<?> clazz) {
    if (clazz.isInterface()) {
      return Collections.singleton(clazz);
    }
    Set<Class<?>> interfaces = new HashSet<>();
    Class<?> current = clazz;
    while (current != null) {
      Class<?>[] ifcs = current.getInterfaces();
      interfaces.addAll(Arrays.asList(ifcs));
      current = current.getSuperclass();
    }
    return interfaces;
  }
}
