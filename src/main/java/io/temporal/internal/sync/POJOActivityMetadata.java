package io.temporal.internal.sync;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.common.MethodRetry;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Rules:
 *
 * <ul>
 *   <li>An activity implementation must implement at least one non empty interface annotated with
 *       ActivityInterface
 *   <li>An interface annotated with ActivityInterface can extend zero or more interfaces.
 *   <li>An interface annotated with ActivityInterface defines activity methods for all methods it
 *       inherited from interfaces which are not annotated with ActivityInterface.
 *   <li>Each method name can be defined only once across all interfaces annotated with
 *       ActivityInterface. So if annotated interface A has method foo() and an annotated interface
 *       B extends A it cannot also declare foo() even with a different signature.
 * </ul>
 */
class POJOActivityMetadata {

  public static class MethodMetadata {
    private final boolean hasActivityMethodAnnotation;
    private final String name;
    private final Method method;
    private final Class<?> interfaceType;

    MethodMetadata(Method method, Class<?> interfaceType) {
      this.method = Objects.requireNonNull(method);
      this.interfaceType = Objects.requireNonNull(interfaceType);
      ActivityMethod activityMethod = method.getAnnotation(ActivityMethod.class);
      String name;
      if (activityMethod != null) {
        hasActivityMethodAnnotation = true;
        name = activityMethod.name();
      } else {
        hasActivityMethodAnnotation = false;
        name = interfaceType.getSimpleName() + "_" + method.getName();
      }
      this.name = name;
    }

    public boolean isHasActivityMethodAnnotation() {
      return hasActivityMethodAnnotation;
    }

    public String getName() {
      if (name == null) {
        throw new IllegalStateException("Not annotated");
      }
      return name;
    }

    public Method getMethod() {
      return method;
    }

    /** Compare and hash based on method and the interface type only. */
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      MethodMetadata that = (MethodMetadata) o;
      return com.google.common.base.Objects.equal(method, that.method)
          && com.google.common.base.Objects.equal(interfaceType, that.interfaceType);
    }

    /** Compare and hash based on method and the interface type only. */
    @Override
    public int hashCode() {
      return com.google.common.base.Objects.hashCode(method, interfaceType);
    }
  }

  /** Used to override equals and hashCode of Method to ensure deduping by method name in a set. */
  static class EqualsByMethodName {
    private final Method method;

    EqualsByMethodName(Method method) {
      this.method = method;
    }

    public Method getMethod() {
      return method;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      EqualsByMethodName that = (EqualsByMethodName) o;
      return com.google.common.base.Objects.equal(method.getName(), that.method.getName());
    }

    @Override
    public int hashCode() {
      return com.google.common.base.Objects.hashCode(method.getName());
    }
  }

  private final Map<Method, MethodMetadata> methods = new HashMap<>();
  private final Map<String, MethodMetadata> byName = new HashMap<>();

  public static POJOActivityMetadata newForImplementation(Class<?> implementationClass) {
    return new POJOActivityMetadata(implementationClass, true);
  }

  public static POJOActivityMetadata newForInterface(Class<?> anInterface) {
    return new POJOActivityMetadata(anInterface, false);
  }

  private POJOActivityMetadata(Class<?> cls, boolean implementation) {
    if (implementation) {
      initActivityImplementation(cls);
    } else {
      initActivityInterface(cls);
    }
  }

  public List<MethodMetadata> getMethodsMetadata() {
    return new ArrayList<>(methods.values());
  }

  public MethodMetadata getMethodMetadata(Method method) {
    MethodMetadata result = methods.get(method);
    if (result == null) {
      throw new IllegalArgumentException("Unknown method: " + method.getName());
    }
    return result;
  }

  private void initActivityImplementation(Class<?> implClass) {
    if (implClass.isInterface()
        || implClass.isPrimitive()
        || implClass.isAnnotation()
        || implClass.isArray()
        || implClass.isEnum()) {
      throw new IllegalArgumentException("concrete class expected: " + implClass);
    }
    for (Method method : implClass.getMethods()) {
      if (method.getAnnotation(ActivityMethod.class) != null) {
        throw new IllegalArgumentException(
            "Found @ActivityMethod annotation on \""
                + method
                + "\" This annotation can be used only on the interface method it implements.");
      }
      if (method.getAnnotation(MethodRetry.class) != null) {
        throw new IllegalArgumentException(
            "Found @MethodRetry annotation on \""
                + method
                + "\" This annotation can be used only on the interface method it implements.");
      }
    }
    Class<?>[] interfaces = implClass.getInterfaces();
    for (int i = 0; i < interfaces.length; i++) {
      Class<?> anInterface = interfaces[i];
      Map<EqualsByMethodName, Method> dedupeMap = new HashMap<>();
      getActivityInterfaceMethods(anInterface, dedupeMap);
    }
    if (this.methods.isEmpty()) {
      throw new IllegalArgumentException(
          "Class doesn't implement any non empty interface annotated with @ActivityInterface: "
              + implClass.getName());
    }
  }

  private void initActivityInterface(Class<?> anInterface) {
    if (!anInterface.isInterface()) {
      throw new IllegalArgumentException("not an interface: " + anInterface);
    }
    ActivityInterface annotation = anInterface.getAnnotation(ActivityInterface.class);
    if (annotation == null) {
      throw new IllegalArgumentException(
          "Missing requied @ActivityInterface annotation: " + anInterface);
    }
    Map<EqualsByMethodName, Method> dedupeMap = new HashMap<>();
    getActivityInterfaceMethods(anInterface, dedupeMap);
    if (this.methods.isEmpty()) {
      throw new IllegalArgumentException(
          "Interface doesn't contain any methods" + anInterface.getName());
    }
  }

  /** @return methods which are not part of an interface annotated with ActivityInterface */
  private Set<MethodMetadata> getActivityInterfaceMethods(
      Class<?> current, Map<EqualsByMethodName, Method> dedupeMap) {
    ActivityInterface annotation = current.getAnnotation(ActivityInterface.class);

    // Set to dedupe the same method due to diamond inheritance
    Set<MethodMetadata> result = new HashSet<>();
    Class<?>[] interfaces = current.getInterfaces();
    for (int i = 0; i < interfaces.length; i++) {
      Class<?> anInterface = interfaces[i];
      Set<MethodMetadata> parentMethods = getActivityInterfaceMethods(anInterface, dedupeMap);
      for (MethodMetadata parentMethod : parentMethods) {
        if (!parentMethod.hasActivityMethodAnnotation) {
          try {
            current.getMethod(parentMethod.getName(), parentMethod.getMethod().getParameterTypes());
            // Don't add to result as it is redefined by current.
            // This allows overriding methods without annotation with annotated methods.
            continue;
          } catch (NoSuchMethodException e) {
          }
        }
        result.add(parentMethod);
      }
    }
    Method[] declaredMethods = current.getDeclaredMethods();
    for (int i = 0; i < declaredMethods.length; i++) {
      Method declaredMethod = declaredMethods[i];
      result.add(new MethodMetadata(declaredMethod, current));
    }
    if (annotation == null) {
      return result; // Not annotated just pass all the methods to the parent
    }
    for (MethodMetadata methodMetadata : result) {
      Method method = methodMetadata.getMethod();
      EqualsByMethodName wrapped = new EqualsByMethodName(method);
      Method registered = dedupeMap.put(wrapped, method);
      if (registered != null) {
        throw new IllegalArgumentException(
            "Duplicated methods (overloads are not allowed): "
                + registered.getName()
                + " and "
                + method.getName());
      }
      methods.put(method, methodMetadata);
      MethodMetadata registeredMM = byName.put(methodMetadata.getName(), methodMetadata);
      if (registeredMM != null) {
        throw new IllegalArgumentException(
            "Duplicated names: "
                + registeredMM.getName()
                + " and "
                + methodMetadata.getName()
                + " declared at "
                + registeredMM.getMethod().getName()
                + " and "
                + methodMetadata.getMethod().getName());
      }
    }
    return Collections.emptySet();
  }
}
