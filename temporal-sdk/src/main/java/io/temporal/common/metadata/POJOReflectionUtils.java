/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.common.metadata;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

  /**
   * Get the resolved generic parameter types for the specified method in the specified class.
   *
   * <p>The class might be a subclass of the method's declaring class. The parameter types will be
   * resolved considering the entire class hierarchy from clazz to the method's declaring class.
   *
   * <p>If a type can't be fully resolved but is a {@link TypeVariable} with only 1 bound, the type
   * variable's bound will be returned instead. If there are multiple bounds, the type variable will
   * be returned.
   *
   * @param method the method to resolve the generic parameter types
   * @param clazz the class to consider for the parameter type resolution
   * @return Resolved generic parameter types
   */
  public static Type[] getGenericParameterTypes(Method method, Class<?> clazz) {
    checkMethodOnClass(method, clazz);

    Map<TypeParameterKey, Type> resolvedTypes = getResolvedTypes(clazz);
    TypeVariable<?>[] genericParameterTypeVariables = getGenericParameterTypeVariables(method);
    Type[] originalGenericParameterTypes = method.getGenericParameterTypes();
    Type[] genericParameterTypes = new Type[genericParameterTypeVariables.length];
    for (int i = 0; i < genericParameterTypeVariables.length; i++) {
      genericParameterTypes[i] =
          genericParameterTypeVariables[i] != null
              ? getResolvedGenericType(genericParameterTypeVariables[i], resolvedTypes)
              : null;
      if (genericParameterTypes[i] == null) {
        genericParameterTypes[i] = originalGenericParameterTypes[i];
      }
    }

    return genericParameterTypes;
  }

  /**
   * Get the resolved generic return type for the specified method in the specified class.
   *
   * <p>The class might be a subclass of the method's declaring class. The return type will be
   * resolved considering the entire class hierarchy from clazz to the method's declaring class.
   *
   * <p>If a type can't be fully resolved but is a {@link TypeVariable} with only 1 bound, the type
   * variable's bound will be returned instead. If there are multiple bounds, the type variable will
   * be returned.
   *
   * @param method the method to resolve the generic return type
   * @param clazz the class to consider for the return type resolution
   * @return Resolved generic return type
   */
  public static Type getGenericReturnType(Method method, Class<?> clazz) {
    checkMethodOnClass(method, clazz);

    Map<TypeParameterKey, Type> resolvedTypes = getResolvedTypes(clazz);
    return getResolvedGenericType(method.getGenericReturnType(), resolvedTypes);
  }

  private static void checkMethodOnClass(Method method, Class<?> clazz) {
    if (!method.getDeclaringClass().isAssignableFrom(clazz)) {
      throw new IllegalArgumentException(
          String.format("Method %s is not present at %s", method, clazz));
    }
  }

  private static TypeVariable<?>[] getGenericParameterTypeVariables(Method method) {
    Type[] genericParameterTypes = method.getGenericParameterTypes();
    TypeVariable<?>[] genericParameterTypesVariable =
        new TypeVariable<?>[genericParameterTypes.length];
    for (int i = 0; i < genericParameterTypes.length; i++) {
      if (genericParameterTypes[i] instanceof TypeVariable<?>) {
        genericParameterTypesVariable[i] = (TypeVariable<?>) genericParameterTypes[i];
      } else {
        genericParameterTypesVariable[i] = null;
      }
    }

    return genericParameterTypesVariable;
  }

  private static Type getResolvedGenericType(
      Type genericReturnType, Map<TypeParameterKey, Type> resolvedTypes) {
    if (genericReturnType instanceof TypeVariable<?>) {
      return getResolvedGenericType((TypeVariable<?>) genericReturnType, resolvedTypes);
    } else if (genericReturnType instanceof ParameterizedType) {
      return getResolvedGenericType((ParameterizedType) genericReturnType, resolvedTypes);
    } else if (genericReturnType instanceof GenericArrayType) {
      return getResolvedGenericType((GenericArrayType) genericReturnType, resolvedTypes);
    } else if (genericReturnType instanceof WildcardType) {
      return getResolvedGenericType((WildcardType) genericReturnType, resolvedTypes);
    } else {
      return genericReturnType;
    }
  }

  private static Type getResolvedGenericType(
      TypeVariable<?> typeVariable, Map<TypeParameterKey, Type> resolvedTypes) {
    Class<?> typeVariableClass = (Class<?>) typeVariable.getGenericDeclaration();
    Type type = resolvedTypes.get(new TypeParameterKey(typeVariableClass, typeVariable.getName()));
    if (type instanceof TypeVariable<?>) {
      Type[] bounds = ((TypeVariable<?>) type).getBounds();
      if (bounds.length == 1) {
        return bounds[0];
      } // If there are multiple bounds we can't really know
    } else if (type instanceof ParameterizedType) {
      return getResolvedGenericType((ParameterizedType) type, resolvedTypes);
    } else if (type instanceof GenericArrayType) {
      return getResolvedGenericType((GenericArrayType) type, resolvedTypes);
    }

    return type;
  }

  private static Type getResolvedGenericType(
      ParameterizedType parameterizedType, Map<TypeParameterKey, Type> resolvedTypes) {
    Type[] resolvedTypeArguments = new Type[parameterizedType.getActualTypeArguments().length];
    int i = 0;
    for (Type typeArgument : parameterizedType.getActualTypeArguments()) {
      Type resolvedType = getResolvedGenericType(typeArgument, resolvedTypes);
      resolvedTypeArguments[i++] = resolvedType;
    }
    return new ParameterizedTypeImpl(
        parameterizedType.getOwnerType(), parameterizedType.getRawType(), resolvedTypeArguments);
  }

  private static Type getResolvedGenericType(
      GenericArrayType genericArrayType, Map<TypeParameterKey, Type> resolvedTypes) {
    return new GenericArrayTypeImpl(
        getResolvedGenericType(genericArrayType.getGenericComponentType(), resolvedTypes));
  }

  private static Type getResolvedGenericType(
      WildcardType wildcardType, Map<TypeParameterKey, Type> resolvedTypes) {
    Type[] bounds = wildcardType.getUpperBounds();
    if (bounds.length == 1) {
      return getResolvedGenericType(bounds[0], resolvedTypes);
    }
    // If there are multiple bounds we can't really know
    return wildcardType;
  }

  static Map<TypeParameterKey, Type> getResolvedTypes(Class<?> clazz) {
    Map<TypeParameterKey, Type> result = new HashMap<>();
    collectResolvedTypes(clazz, result);
    return result;
  }

  private static void collectResolvedTypes(
      Class<?> clazz, Map<TypeParameterKey, Type> resolvedTypes) {
    for (Type genericInterface : clazz.getGenericInterfaces()) {
      if (genericInterface instanceof ParameterizedType) {
        // The simplest case:
        // InterfaceA<T>
        // InterfaceB extends InterfaceA<MyType>
        // ResolvedType(InterfaceA,T) <= MyType
        ParameterizedType parameterizedInterface = (ParameterizedType) genericInterface;
        int interfaceTypeVariableIndex = 0;
        Type[] actualTypeArguments = parameterizedInterface.getActualTypeArguments();
        for (TypeVariable<?> typeParameter :
            ((Class<?>) parameterizedInterface.getRawType()).getTypeParameters()) {
          Type currentResolvedType = actualTypeArguments[interfaceTypeVariableIndex];
          if (currentResolvedType instanceof TypeVariable) {
            TypeParameterKey typeParameterOnChild =
                new TypeParameterKey(clazz, ((TypeVariable<?>) currentResolvedType).getName());
            if (resolvedTypes.containsKey(typeParameterOnChild)) {
              // The case where type variables are "linked":
              // InterfaceA<T>
              // InterfaceB<T> extends InterfaceA<T> <= when processing this
              // InterfaceC extends InterfaceB<MyType>
              // ResolvedType(InterfaceA,T) <= ResolvedType(InterfaceB,T) = MyType
              currentResolvedType = resolvedTypes.get(typeParameterOnChild);
            }
          }
          resolvedTypes.putIfAbsent(
              new TypeParameterKey(
                  (Class<?>) parameterizedInterface.getRawType(), typeParameter.getName()),
              currentResolvedType);
          interfaceTypeVariableIndex++;
        }
      }
    }

    // Collect on parents
    for (Class<?> parentInterface : clazz.getInterfaces()) {
      collectResolvedTypes(parentInterface, resolvedTypes);
    }

    // If clazz has type parameters, collect them
    for (TypeVariable<?> typeParameter : clazz.getTypeParameters()) {
      resolvedTypes.putIfAbsent(
          new TypeParameterKey(clazz, typeParameter.getName()), typeParameter);
    }
  }

  static class TypeParameterKey {
    private final Class<?> clazz;
    private final String name;

    public TypeParameterKey(Class<?> clazz, String name) {
      this.clazz = clazz;
      this.name = name;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof TypeParameterKey)) {
        return false;
      }
      TypeParameterKey that = (TypeParameterKey) o;
      return Objects.equals(clazz, that.clazz) && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(clazz, name);
    }

    @Override
    public String toString() {
      return clazz.getSimpleName() + "<" + name + ">";
    }
  }

  static class GenericArrayTypeImpl implements GenericArrayType {
    private final Type componentType;

    public GenericArrayTypeImpl(Type componentType) {
      this.componentType = componentType;
    }

    @Override
    public Type getGenericComponentType() {
      return componentType;
    }

    @Override
    public String toString() {
      return this.componentType.getTypeName() + "[]";
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof GenericArrayType)) {
        return false;
      }
      GenericArrayType that = (GenericArrayType) o;
      return Objects.equals(componentType, that.getGenericComponentType());
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(componentType);
    }
  }

  static class ParameterizedTypeImpl implements ParameterizedType {
    private final Type ownerType;
    private final Type rawType;
    private final Type[] typeArguments;

    public ParameterizedTypeImpl(Type ownerType, Type rawType, Type... typeArguments) {
      this.ownerType = ownerType;
      this.rawType = rawType;
      this.typeArguments = typeArguments;
    }

    @Override
    public Type[] getActualTypeArguments() {
      return typeArguments;
    }

    @Override
    public Type getRawType() {
      return rawType;
    }

    @Override
    public Type getOwnerType() {
      return ownerType;
    }

    @Override
    public String toString() {
      return this.rawType.getTypeName()
          + '<'
          + String.join(
              ", ", Arrays.stream(typeArguments).map(Type::getTypeName).toArray(String[]::new))
          + '>';
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof ParameterizedType)) {
        return false;
      }
      ParameterizedType that = (ParameterizedType) o;
      return Objects.equals(ownerType, that.getOwnerType())
          && Objects.equals(rawType, that.getRawType())
          && Objects.deepEquals(typeArguments, that.getActualTypeArguments());
    }

    @Override
    public int hashCode() {
      return Objects.hash(ownerType, rawType, Arrays.hashCode(typeArguments));
    }
  }
}
