package io.temporal.internal.sync;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Rules:
 *
 * <ul>
 *   <li>A workflow implementation must implement at least one non empty interface annotated with
 *       WorkflowInterface
 *   <li>An interface annotated with WorkflowInterface can extend zero or more interfaces.
 *   <li>An interface annotated with WorkflowInterface defines workflow methods for all methods it
 *       inherited from interfaces which are not annotated with WorkflowInterface.
 *   <li>Each method name can be defined only once across all interfaces annotated with
 *       WorkflowInterface. So if annotated interface A has method foo() and an annotated interface
 *       B extends A it cannot also declare foo() even with a different signature.
 * </ul>
 */
class POJOWorkflowMetadata {

  public enum WorkflowMethodType {
    NONE,
    WORKFLOW,
    SIGNAL,
    QUERY
  }

  public static class MethodMetadata {
    private final WorkflowMethodType type;
    private final String name;
    private final Method method;
    private final Class<?> interfaceType;

    MethodMetadata(Method method, Class<?> interfaceType) {
      this.method = Objects.requireNonNull(method);
      this.interfaceType = Objects.requireNonNull(interfaceType);
      WorkflowMethod workflowMethod = method.getAnnotation(WorkflowMethod.class);
      QueryMethod queryMethod = method.getAnnotation(QueryMethod.class);
      SignalMethod signalMethod = method.getAnnotation(SignalMethod.class);
      int count = 0;
      WorkflowMethodType type = null;
      String name = null;
      if (workflowMethod != null) {
        type = WorkflowMethodType.WORKFLOW;
        count++;
        name = workflowMethod.name();
      }
      if (signalMethod != null) {
        type = WorkflowMethodType.SIGNAL;
        if (method.getReturnType() != Void.TYPE) {
          throw new IllegalArgumentException(
              "Method annotated with @SignalMethod must have void return type: " + method);
        }
        count++;
        name = signalMethod.name();
      }
      if (queryMethod != null) {
        type = WorkflowMethodType.QUERY;
        if (method.getReturnType() == Void.TYPE) {
          throw new IllegalArgumentException(
              "Method annotated with @QueryMethod cannot have void return type: " + method);
        }
        count++;
        name = queryMethod.name();
      }
      if (count == 0) {
        type = WorkflowMethodType.NONE;
      } else if (count > 1) {
        throw new IllegalArgumentException(
            method
                + " must contain exactly one annotation "
                + "of @WorkflowMethod, @QueryMethod or @SignalMethod");
      } else {
        if (name.isEmpty()) {
          name = interfaceType.getSimpleName() + "_" + method.getName();
        }
      }
      this.name = name;
      this.type = Objects.requireNonNull(type);
    }

    public WorkflowMethodType getType() {
      return type;
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

  static class EqualsByNameType {
    private final String name;
    private final WorkflowMethodType type;

    EqualsByNameType(String name, WorkflowMethodType type) {
      this.name = name;
      this.type = type;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      EqualsByNameType that = (EqualsByNameType) o;
      return com.google.common.base.Objects.equal(name, that.name) && type == that.type;
    }

    @Override
    public int hashCode() {
      return com.google.common.base.Objects.hashCode(name, type);
    }
  }

  private MethodMetadata workflowMethod;
  private final Map<Method, MethodMetadata> methods = new HashMap<>();
  private final Map<EqualsByNameType, MethodMetadata> byNameType = new HashMap<>();

  public static POJOWorkflowMetadata newForImplementation(Class<?> implementationClass) {
    return new POJOWorkflowMetadata(implementationClass, true);
  }

  public static POJOWorkflowMetadata newForInterface(Class<?> anInterface) {
    return new POJOWorkflowMetadata(anInterface, false);
  }

  private POJOWorkflowMetadata(Class<?> cls, boolean implementation) {
    if (implementation) {
      initWorkflowImplementation(cls);
    } else {
      initWorkflowInterface(cls);
    }
  }

  public Optional<MethodMetadata> getWorkflowMethod() {
    return Optional.ofNullable(workflowMethod);
  }

  public Optional<String> getWorkflowType() {
    if (workflowMethod == null) {
      return Optional.empty();
    }
    return Optional.of(workflowMethod.getName());
  }

  public MethodMetadata getMethodMetadata(Method method) {
    MethodMetadata result = methods.get(method);
    if (result == null) {
      throw new IllegalArgumentException("Unknown method: " + method.getName());
    }
    return result;
  }

  public List<MethodMetadata> getMethodsMetadata(WorkflowMethodType type) {
    List<MethodMetadata> result = new ArrayList<>();
    for (MethodMetadata methodMetadata : this.methods.values()) {
      if (methodMetadata.getType() == type) {
        result.add(methodMetadata);
      }
    }
    return result;
  }

  public List<MethodMetadata> getMethodsMetadata() {
    return new ArrayList<>(this.methods.values());
  }

  private void initWorkflowImplementation(Class<?> implClass) {
    if (implClass.isInterface()
        || implClass.isPrimitive()
        || implClass.isAnnotation()
        || implClass.isArray()
        || implClass.isEnum()) {
      throw new IllegalArgumentException("concrete class expected: " + implClass);
    }
    Class<?>[] interfaces = implClass.getInterfaces();
    for (int i = 0; i < interfaces.length; i++) {
      Class<?> anInterface = interfaces[i];
      Map<EqualsByMethodName, Method> dedupeMap = new HashMap<>();
      getWorkflowInterfaceMethods(anInterface, dedupeMap);
    }
    if (this.methods.isEmpty()) {
      throw new IllegalArgumentException(
          "Class doesn't implement any non empty interface annotated with @WorkflowInterface: "
              + implClass.getName());
    }
  }

  private void initWorkflowInterface(Class<?> anInterface) {
    if (!anInterface.isInterface()) {
      throw new IllegalArgumentException("not an interface: " + anInterface);
    }
    WorkflowInterface annotation = anInterface.getAnnotation(WorkflowInterface.class);
    if (annotation == null) {
      throw new IllegalArgumentException(
          "Missing requied @WorkflowInterface annotation: " + anInterface);
    }
    Map<EqualsByMethodName, Method> dedupeMap = new HashMap<>();
    getWorkflowInterfaceMethods(anInterface, dedupeMap);
    if (this.methods.isEmpty()) {
      throw new IllegalArgumentException(
          "Interface doesn't contain any methods" + anInterface.getName());
    }
  }

  /** @return methods which are not part of an interface annotated with WorkflowInterface */
  private Set<MethodMetadata> getWorkflowInterfaceMethods(
      Class<?> current, Map<EqualsByMethodName, Method> dedupeMap) {
    WorkflowInterface annotation = current.getAnnotation(WorkflowInterface.class);

    // Set to dedupe the same method due to diamond inheritance
    Set<MethodMetadata> result = new HashSet<>();
    Class<?>[] interfaces = current.getInterfaces();
    for (int i = 0; i < interfaces.length; i++) {
      Class<?> anInterface = interfaces[i];
      Set<MethodMetadata> parentMethods = getWorkflowInterfaceMethods(anInterface, dedupeMap);
      for (MethodMetadata parentMethod : parentMethods) {
        if (parentMethod.getType() == WorkflowMethodType.NONE) {
          try {
            current.getMethod(parentMethod.getName(), parentMethod.getMethod().getParameterTypes());
            // Don't add to result as it is redefined by current.
            // This allows overriding methods without annotation with annotated methods.
            continue;
          } catch (NoSuchMethodException e) {
            if (annotation != null) {
              throw new IllegalArgumentException(
                  "Missing @WorkflowMethod, @SignalMethod or @QueryMethod annotation on "
                      + parentMethod.getMethod().getName());
            }
          }
        }
        result.add(parentMethod);
      }
    }
    Method[] declaredMethods = current.getDeclaredMethods();
    for (int i = 0; i < declaredMethods.length; i++) {
      Method declaredMethod = declaredMethods[i];
      MethodMetadata methodMetadata = new MethodMetadata(declaredMethod, current);
      if (methodMetadata.getType() == WorkflowMethodType.WORKFLOW) {
        if (this.workflowMethod != null) {
          throw new IllegalArgumentException(
              "Duplicated @WorkflowMethod: "
                  + methodMetadata.getMethod().getName()
                  + " and "
                  + this.workflowMethod.getMethod().getName());
        }
      }
      result.add(methodMetadata);
    }
    if (annotation == null) {
      return result; // Not annotated just pass all the methods to the parent
    }
    for (MethodMetadata methodMetadata : result) {
      Method method = methodMetadata.getMethod();
      if (methodMetadata.getType() == WorkflowMethodType.NONE) {
        throw new IllegalArgumentException(
            "Missing @WorkflowMethod, @SignalMethod or @QueryMethod annotation on " + method);
      }
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
      MethodMetadata registeredMM =
          byNameType.put(
              new EqualsByNameType(methodMetadata.getName(), methodMetadata.getType()),
              methodMetadata);
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
