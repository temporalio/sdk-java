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

import com.google.common.collect.ImmutableMap;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import junitparams.JUnitParamsRunner;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class POJOReflectionUtilsTest {

  @Test
  public void testResolveNotResolved() {
    Map<POJOReflectionUtils.TypeParameterKey, Type> resolvedTypes =
        POJOReflectionUtils.getResolvedTypes(InterfaceA.class);

    Assert.assertEquals(
        ImmutableMap.of(
            new POJOReflectionUtils.TypeParameterKey(InterfaceA.class, "T"),
            InterfaceA.class.getTypeParameters()[0]),
        resolvedTypes);
  }

  @Test
  public void testResolveOnChildNotResolved() {
    Map<POJOReflectionUtils.TypeParameterKey, Type> resolvedTypes =
        POJOReflectionUtils.getResolvedTypes(InterfaceAChildNotResolved.class);
    Assert.assertNotNull(resolvedTypes);
    Assert.assertEquals(2, resolvedTypes.size());
    Assert.assertEquals(
        ImmutableMap.of(
            new POJOReflectionUtils.TypeParameterKey(InterfaceA.class, "T"),
            InterfaceAChildNotResolved.class.getTypeParameters()[0],
            new POJOReflectionUtils.TypeParameterKey(InterfaceAChildNotResolved.class, "T"),
            InterfaceAChildNotResolved.class.getTypeParameters()[0]),
        resolvedTypes);
  }

  @Test
  public void testResolveOnChildResolvedOnChild() {
    Map<POJOReflectionUtils.TypeParameterKey, Type> resolvedTypes =
        POJOReflectionUtils.getResolvedTypes(InterfaceAChild.class);
    Assert.assertEquals(
        ImmutableMap.of(
            new POJOReflectionUtils.TypeParameterKey(InterfaceA.class, "T"), String.class),
        resolvedTypes);
  }

  @Test
  public void testResolveOnGrandChildResolvedOnGrandChild() {
    Map<POJOReflectionUtils.TypeParameterKey, Type> resolvedTypes =
        POJOReflectionUtils.getResolvedTypes(InterfaceAGrandChild.class);
    Assert.assertEquals(
        ImmutableMap.of(
            new POJOReflectionUtils.TypeParameterKey(InterfaceA.class, "T"), String.class,
            new POJOReflectionUtils.TypeParameterKey(InterfaceAChildNotResolved.class, "T"),
                String.class),
        resolvedTypes);
  }

  @Test
  public void testResolveOnGrandChildResolvedOnChild() {
    Map<POJOReflectionUtils.TypeParameterKey, Type> resolvedTypes =
        POJOReflectionUtils.getResolvedTypes(InterfaceAGrandChildResolvedByChild.class);
    Assert.assertEquals(
        ImmutableMap.of(
            new POJOReflectionUtils.TypeParameterKey(InterfaceA.class, "T"), String.class),
        resolvedTypes);
  }

  @Test
  public void testResolveOnExtensionWithSameParameterTypeName() {
    Map<POJOReflectionUtils.TypeParameterKey, Type> resolvedTypes =
        POJOReflectionUtils.getResolvedTypes(InterfaceAExtension.class);
    Assert.assertEquals(
        ImmutableMap.of(
            new POJOReflectionUtils.TypeParameterKey(InterfaceA.class, "T"),
            String.class,
            new POJOReflectionUtils.TypeParameterKey(InterfaceAExtension.class, "T"),
            InterfaceAExtension.class.getTypeParameters()[0]),
        resolvedTypes);
  }

  @Test
  public void testResolveOnExtensionChildWithSameParameterTypeName() {
    Map<POJOReflectionUtils.TypeParameterKey, Type> resolvedTypes =
        POJOReflectionUtils.getResolvedTypes(InterfaceAExtensionChild.class);
    Assert.assertEquals(
        ImmutableMap.of(
            new POJOReflectionUtils.TypeParameterKey(InterfaceA.class, "T"),
            String.class,
            new POJOReflectionUtils.TypeParameterKey(InterfaceAExtension.class, "T"),
            Number.class),
        resolvedTypes);
  }

  @Test
  public void testResolveNotResolvedButBound() {
    Map<POJOReflectionUtils.TypeParameterKey, Type> resolvedTypes =
        POJOReflectionUtils.getResolvedTypes(InterfaceC.class);
    Assert.assertEquals(
        ImmutableMap.of(
            new POJOReflectionUtils.TypeParameterKey(InterfaceC.class, "T"),
            InterfaceC.class.getTypeParameters()[0]),
        resolvedTypes);
  }

  @Test
  public void testGetGenericReturnTypeNotResolved() throws NoSuchMethodException {
    Type type = getReturnTypeForInterfaceMethod(InterfaceA.class, InterfaceA.class);
    Assert.assertEquals(type, Object.class);
  }

  @Test
  public void testGetGenericReturnTypeOnChildNotResolved() throws NoSuchMethodException {
    Type type = getReturnTypeForInterfaceMethod(InterfaceA.class, InterfaceAChildNotResolved.class);
    Assert.assertEquals(type, Object.class);
  }

  @Test
  public void testGetGenericReturnTypeOnChild() throws NoSuchMethodException {
    Type type = getReturnTypeForInterfaceMethod(InterfaceA.class, InterfaceAChild.class);
    Assert.assertEquals(String.class, type);
  }

  @Test
  public void testGetGenericReturnTypeOnGrandChild() throws NoSuchMethodException {
    Type type = getReturnTypeForInterfaceMethod(InterfaceA.class, InterfaceAGrandChild.class);
    Assert.assertEquals(String.class, type);
  }

  @Test
  public void testGetGenericReturnTypeOnGrandChildResolvedByChild() throws NoSuchMethodException {
    Type type =
        getReturnTypeForInterfaceMethod(
            InterfaceA.class, InterfaceAGrandChildResolvedByChild.class);
    Assert.assertEquals(String.class, type);
  }

  @Test
  public void testGetGenericReturnTypeOnExtensionWithSameParameterTypeName()
      throws NoSuchMethodException {
    Type type = getReturnTypeForInterfaceMethod(InterfaceA.class, InterfaceAExtension.class);
    Assert.assertEquals(String.class, type);

    Type type2 =
        getReturnTypeForInterfaceMethod(
            InterfaceAExtension.class, InterfaceAExtension.class, "method2");
    Assert.assertEquals(Object.class, type2);
  }

  @Test
  public void testGetGenericReturnTypeOnExtensionChildWithSameParameterTypeName()
      throws NoSuchMethodException {
    Type type = getReturnTypeForInterfaceMethod(InterfaceA.class, InterfaceAExtensionChild.class);
    Assert.assertEquals(String.class, type);

    Type type2 =
        getReturnTypeForInterfaceMethod(
            InterfaceAExtension.class, InterfaceAExtensionChild.class, "method2");
    Assert.assertEquals(Number.class, type2);
  }

  @Test
  public void testGetGenericParameterTypesNotResolved() throws NoSuchMethodException {
    Type[] types = getParamTypesForInterfaceMethod(InterfaceB.class, InterfaceB.class);
    Assert.assertEquals(Object.class, types[0]);
    Assert.assertEquals(Object.class, types[1]);
  }

  @Test
  public void testGetGenericParameterTypesOnChildNotResolved() throws NoSuchMethodException {
    Type[] types =
        getParamTypesForInterfaceMethod(InterfaceB.class, InterfaceBChildNotResolved.class);
    Assert.assertEquals(Object.class, types[0]);
    Assert.assertEquals(Object.class, types[1]);
  }

  @Test
  public void testGetGenericParameterTypesOnChildResolvedPartially() throws NoSuchMethodException {
    Type[] types =
        getParamTypesForInterfaceMethod(InterfaceB.class, InterfaceBChildPartiallyResolved.class);
    Assert.assertEquals(Object.class, types[0]);
    Assert.assertEquals(Integer.class, types[1]);
  }

  @Test
  public void testGetGenericParameterTypesOnChild() throws NoSuchMethodException {
    Type[] types = getParamTypesForInterfaceMethod(InterfaceB.class, InterfaceBChild.class);
    Assert.assertEquals(String.class, types[0]);
    Assert.assertEquals(Integer.class, types[1]);
  }

  @Test
  public void testGetGenericParameterTypesOnGrandChild() throws NoSuchMethodException {
    Type[] types = getParamTypesForInterfaceMethod(InterfaceB.class, InterfaceBGrandChild.class);
    Assert.assertEquals(String.class, types[0]);
    Assert.assertEquals(Integer.class, types[1]);
  }

  @Test
  public void testGetGenericParameterTypesOnGrandChild2() throws NoSuchMethodException {
    Type[] types = getParamTypesForInterfaceMethod(InterfaceB.class, InterfaceBGrandChild2.class);
    Assert.assertEquals(String.class, types[0]);
    Assert.assertEquals(Integer.class, types[1]);
  }

  @Test
  public void testGetGenericParameterTypesOnGrandChildResolvedByChild()
      throws NoSuchMethodException {
    Type[] types =
        getParamTypesForInterfaceMethod(
            InterfaceB.class, InterfaceBGrandChildResolvedByChild.class);
    Assert.assertEquals(String.class, types[0]);
    Assert.assertEquals(Integer.class, types[1]);
  }

  @Test
  public void testGetGenericReturnTypeNotResolvedButBound() throws NoSuchMethodException {
    Type type = getReturnTypeForInterfaceMethod(InterfaceC.class, InterfaceC.class);
    Assert.assertEquals(type, Number.class);
  }

  @Test
  public void testGetGenericReturnBoundAndResolvedOnChild() throws NoSuchMethodException {
    Type type = getReturnTypeForInterfaceMethod(InterfaceC.class, InterfaceCChild.class);
    Assert.assertEquals(Double.class, type);
  }

  @Test
  public void testGetGenericReturnTypeOfParameterizedTypeOnChild() throws NoSuchMethodException {
    Type type = getReturnTypeForInterfaceMethod(InterfaceD.class, InterfaceDChild.class);
    Assert.assertEquals(
        new POJOReflectionUtils.ParameterizedTypeImpl(null, Map.class, String.class, Double.class),
        type);
  }

  @Test
  public void testGetGenericReturnTypeOfParameterizedTypeOnTypeVariable()
      throws NoSuchMethodException {
    Type type = getReturnTypeForInterfaceMethod(InterfaceD.class, InterfaceDGrandChild.class);
    Assert.assertEquals(
        new POJOReflectionUtils.ParameterizedTypeImpl(
            null,
            Map.class,
            Integer.class,
            new POJOReflectionUtils.ParameterizedTypeImpl(
                null, Map.class, String.class, Double.class)),
        type);
  }

  @Test
  public void testGetGenericReturnTypeWithMultipleBounds() throws NoSuchMethodException {
    Type type = getReturnTypeForInterfaceMethod(InterfaceE.class, InterfaceE.class);
    Assert.assertEquals(InterfaceE.class.getTypeParameters()[0], type);
  }

  @Test
  public void testGetGenericReturnMultipleBoundAndResolvedOnChild() throws NoSuchMethodException {
    Type type = getReturnTypeForInterfaceMethod(InterfaceE.class, InterfaceEChild.class);
    Assert.assertEquals(Double.class, type);
  }

  @Test
  public void testGetGenericArrayTypeNotResolved() throws NoSuchMethodException {
    Type type = getReturnTypeForInterfaceMethod(InterfaceF.class, InterfaceF.class);
    Assert.assertEquals(new POJOReflectionUtils.GenericArrayTypeImpl(Object.class), type);
  }

  @Test
  public void testGetGenericArrayTypeOnChild() throws NoSuchMethodException {
    Type type = getReturnTypeForInterfaceMethod(InterfaceF.class, InterfaceFChild.class);
    Assert.assertEquals(new POJOReflectionUtils.GenericArrayTypeImpl(String.class), type);
  }

  @Test
  public void testGetGenericArrayTypeOnGrandChild() throws NoSuchMethodException {
    Type type = getReturnTypeForInterfaceMethod(InterfaceF.class, InterfaceFGrandChild.class);
    Assert.assertEquals(
        new POJOReflectionUtils.GenericArrayTypeImpl(
            new POJOReflectionUtils.ParameterizedTypeImpl(null, List.class, String.class)),
        type);
  }

  @Test
  public void testGetGenericArrayTypeOnGrandChild2() throws NoSuchMethodException {
    Type type = getReturnTypeForInterfaceMethod(InterfaceF.class, InterfaceFGrandChild2.class);
    Assert.assertEquals(
        new POJOReflectionUtils.GenericArrayTypeImpl(
            new POJOReflectionUtils.GenericArrayTypeImpl(String.class)),
        type);
  }

  @Test
  public void testGetWildCardTypeNotResolved() throws NoSuchMethodException {
    Type type = getReturnTypeForInterfaceMethod(InterfaceG.class, InterfaceG.class);
    Assert.assertEquals(
        new POJOReflectionUtils.ParameterizedTypeImpl(null, List.class, Object.class), type);
  }

  @Test
  public void testGetWildCardTypeWithNoUpperBoundNotResolved() throws NoSuchMethodException {
    Type type = getReturnTypeForInterfaceMethod(InterfaceG.class, InterfaceG.class, "method2");
    Assert.assertEquals(
        new POJOReflectionUtils.ParameterizedTypeImpl(null, List.class, Object.class), type);
  }

  @Test
  public void testGetWildCardTypeOnChild() throws NoSuchMethodException {
    Type type = getReturnTypeForInterfaceMethod(InterfaceG.class, InterfaceGChild.class);
    Assert.assertEquals(
        new POJOReflectionUtils.ParameterizedTypeImpl(null, List.class, Integer.class), type);
  }

  @Test
  public void testGetWildCardWithNoUpperBoundTypeOnChild() throws NoSuchMethodException {
    Type type = getReturnTypeForInterfaceMethod(InterfaceG.class, InterfaceGChild.class, "method2");
    Assert.assertEquals(
        new POJOReflectionUtils.ParameterizedTypeImpl(null, List.class, Object.class), type);
  }

  private Type[] getParamTypesForInterfaceMethod(Class<?> parentInterface, Class<?> childInterface)
      throws NoSuchMethodException {
    return POJOReflectionUtils.getGenericParameterTypes(
        parentInterface.getMethod("method", Object.class, Object.class), childInterface);
  }

  private Type getReturnTypeForInterfaceMethod(Class<?> parentInterface, Class<?> childInterface)
      throws NoSuchMethodException {
    return getReturnTypeForInterfaceMethod(parentInterface, childInterface, "method");
  }

  private Type getReturnTypeForInterfaceMethod(
      Class<?> parentInterface, Class<?> childInterface, String methodName)
      throws NoSuchMethodException {
    return POJOReflectionUtils.getGenericReturnType(
        parentInterface.getMethod(methodName), childInterface);
  }

  public interface InterfaceA<T> {
    T method();
  }

  public interface InterfaceAChildNotResolved<T> extends InterfaceA<T> {}

  public interface InterfaceAChild extends InterfaceA<String> {}

  public interface InterfaceAGrandChild extends InterfaceAChildNotResolved<String> {}

  public interface InterfaceAGrandChildResolvedByChild extends InterfaceAChild {}

  public interface InterfaceAExtension<T> extends InterfaceA<String> {
    T method2();
  }

  public interface InterfaceAExtensionChild extends InterfaceAExtension<Number> {}

  public interface InterfaceB<T, V> {
    void method(T arg1, V arg2);
  }

  public interface InterfaceBChildNotResolved<T, V> extends InterfaceB<T, V> {}

  public interface InterfaceBChildPartiallyResolved<T> extends InterfaceB<T, Integer> {}

  public interface InterfaceBChild extends InterfaceB<String, Integer> {}

  public interface InterfaceBGrandChild extends InterfaceBChildNotResolved<String, Integer> {}

  public interface InterfaceBGrandChild2 extends InterfaceBChildPartiallyResolved<String> {}

  public interface InterfaceBGrandChildResolvedByChild extends InterfaceBChild {}

  public interface InterfaceC<T extends Number> {
    T method();
  }

  public interface InterfaceCChild extends InterfaceC<Double> {}

  public interface InterfaceD<T, V> {
    Map<T, V> method();
  }

  public interface InterfaceDChild extends InterfaceD<String, Double> {}

  public interface InterfaceDChildPartiallyResolved<T, V> extends InterfaceD<Integer, Map<T, V>> {}

  public interface InterfaceDGrandChild extends InterfaceDChildPartiallyResolved<String, Double> {}

  public interface InterfaceE<T extends Number & Comparable<? extends Number>> {
    T method();
  }

  public interface InterfaceEChild extends InterfaceE<Double> {}

  public interface InterfaceF<T> {
    T[] method();
  }

  public interface InterfaceFChild extends InterfaceF<String> {}

  public interface InterfaceFChildResolvedToParameterizedType<T> extends InterfaceF<List<T>> {}

  public interface InterfaceFChildResolvedToGenericArrayType<T> extends InterfaceF<T[]> {}

  public interface InterfaceFGrandChild
      extends InterfaceFChildResolvedToParameterizedType<String> {}

  public interface InterfaceFGrandChild2
      extends InterfaceFChildResolvedToGenericArrayType<String> {}

  public interface InterfaceG<T> {
    List<? extends T> method();

    List<? super T> method2();
  }

  public interface InterfaceGChild extends InterfaceG<Integer> {}
}
