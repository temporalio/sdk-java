package io.temporal.common.metadata;

import com.google.common.collect.ImmutableList;
import io.temporal.activity.ActivityMethod;
import io.temporal.common.MethodRetry;
import io.temporal.internal.common.InternalUtils;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Metadata of an activity implementation object.
 *
 * <p>Rules:
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
public final class POJOActivityImplMetadata {

  private final List<POJOActivityInterfaceMetadata> activityInterfaces;
  private final List<POJOActivityMethodMetadata> activityMethods;

  /** Creates POJOActivityImplMetadata for an activity implementation class. */
  public static POJOActivityImplMetadata newInstance(Class<?> implementationClass) {
    return new POJOActivityImplMetadata(implementationClass);
  }

  private POJOActivityImplMetadata(Class<?> implClass) {
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
    List<POJOActivityInterfaceMetadata> activityInterfaces = new ArrayList<>();
    Map<String, POJOActivityMethodMetadata> byName = new HashMap<>();

    // Getting all the top level interfaces instead of the direct ones that Class.getInterfaces()
    // returns
    Set<Class<?>> interfaces = POJOReflectionUtils.getTopLevelInterfaces(implClass);
    for (Class<?> anInterface : interfaces) {
      POJOActivityInterfaceMetadata interfaceMetadata =
          POJOActivityInterfaceMetadata.newImplementationInterface(anInterface);
      activityInterfaces.add(interfaceMetadata);
      List<POJOActivityMethodMetadata> methods = interfaceMetadata.getMethodsMetadata();
      for (POJOActivityMethodMetadata methodMetadata : methods) {
        InternalUtils.checkMethodName(methodMetadata);
        POJOActivityMethodMetadata registeredMM =
            byName.put(methodMetadata.getActivityTypeName(), methodMetadata);
        if (registeredMM != null && !registeredMM.equals(methodMetadata)) {
          throw new IllegalArgumentException(
              "Duplicated name: \""
                  + methodMetadata.getActivityTypeName()
                  + "\" declared at \""
                  + registeredMM.getMethod()
                  + "\" registered through \""
                  + registeredMM.getInterfaceType()
                  + "\" and \""
                  + methodMetadata.getMethod()
                  + "\" registered through \""
                  + methodMetadata.getInterfaceType()
                  + "\"");
        }
      }
    }
    if (byName.isEmpty()) {
      throw new IllegalArgumentException(
          "Class doesn't implement any non empty interface annotated with @ActivityInterface: "
              + implClass.getName());
    }
    this.activityInterfaces = ImmutableList.copyOf(activityInterfaces);
    this.activityMethods = ImmutableList.copyOf(byName.values());
  }

  /** Activity interfaces implemented by the object. */
  public List<POJOActivityInterfaceMetadata> getActivityInterfaces() {
    return activityInterfaces;
  }

  /** Activity methods implemented by the object */
  public List<POJOActivityMethodMetadata> getActivityMethods() {
    return activityMethods;
  }
}
