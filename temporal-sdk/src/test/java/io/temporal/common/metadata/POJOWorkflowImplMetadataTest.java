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

import static org.junit.Assert.*;

import com.google.common.collect.ImmutableSet;
import io.temporal.common.converter.EncodedValuesTest;
import io.temporal.workflow.WorkflowInit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

public class POJOWorkflowImplMetadataTest {
  @Test
  public void testMetadataD() {
    Set<String> wExpected = ImmutableSet.of("AM_C_bb", "AM_E_bb");
    Set<String> qExpected = ImmutableSet.of("b");
    Set<String> sExpected = ImmutableSet.of("a", "c", "d");

    Set<String> wTypes = new HashSet<>();
    Set<String> qTypes = new HashSet<>();
    Set<String> sTypes = new HashSet<>();
    POJOWorkflowImplMetadata implMetadata = POJOWorkflowImplMetadata.newInstance(DImpl.class);
    for (POJOWorkflowInterfaceMetadata workflowInterface : implMetadata.getWorkflowInterfaces()) {
      for (POJOWorkflowMethodMetadata methodMetadata : workflowInterface.getMethodsMetadata()) {
        String name = methodMetadata.getName();
        WorkflowMethodType type = methodMetadata.getType();
        switch (type) {
          case WORKFLOW:
            wTypes.add(name);
            break;
          case QUERY:
            qTypes.add(name);
            break;
          case SIGNAL:
            sTypes.add(name);
            break;
          default:
            throw new IllegalArgumentException("Unknown type: " + type);
        }
      }
    }

    assertEquals(wExpected, wTypes);
    assertEquals(sExpected, sTypes);
    assertEquals(qExpected, qTypes);
  }

  /**
   * Extending an implementation with a completely irrelevant non-workflow interface should not
   * cause any changes. D and DWithAdditionalInterface should give the same final metadata.
   */
  @Test
  public void testMetadataDWithAdditionalInterface() {
    Set<String> wExpected = ImmutableSet.of("AM_C_bb", "AM_E_bb");
    Set<String> qExpected = ImmutableSet.of("b");
    Set<String> sExpected = ImmutableSet.of("a", "c", "d");

    Set<String> wTypes = new HashSet<>();
    Set<String> qTypes = new HashSet<>();
    Set<String> sTypes = new HashSet<>();
    POJOWorkflowImplMetadata implMetadata =
        POJOWorkflowImplMetadata.newInstance(DWithAdditionalInterface.class);
    for (POJOWorkflowInterfaceMetadata workflowInterface : implMetadata.getWorkflowInterfaces()) {
      for (POJOWorkflowMethodMetadata methodMetadata : workflowInterface.getMethodsMetadata()) {
        String name = methodMetadata.getName();
        WorkflowMethodType type = methodMetadata.getType();
        switch (type) {
          case WORKFLOW:
            wTypes.add(name);
            break;
          case QUERY:
            qTypes.add(name);
            break;
          case SIGNAL:
            sTypes.add(name);
            break;
          default:
            throw new IllegalArgumentException("Unknown type: " + type);
        }
      }
    }

    assertEquals(wExpected, wTypes);
    assertEquals(sExpected, sTypes);
    assertEquals(qExpected, qTypes);

    List<POJOWorkflowInterfaceMetadata> workflowInterfaces = implMetadata.getWorkflowInterfaces();
    workflowInterfaces.forEach(
        metadata ->
            assertNotEquals(
                "O is not a workflow interface and shouldn't be returned",
                metadata.getInterfaceClass(),
                POJOWorkflowInterfaceMetadataTest.O.class));
  }

  @Test
  public void testNonPublicInterface() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> POJOWorkflowImplMetadata.newInstance(GImpl.class));
    assertTrue(
        e.getMessage(),
        e.getMessage().contains("Interface with @WorkflowInterface annotation must be public"));
  }

  @Test
  public void testWorkflowImplWithDuplicatedWorkflowTypes() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> POJOWorkflowImplMetadata.newInstance(DuplicatedName1Impl.class));
    assertTrue(e.getMessage(), e.getMessage().contains("bb()"));

    e =
        assertThrows(
            IllegalArgumentException.class,
            () -> POJOWorkflowImplMetadata.newInstance(DuplicatedName2Impl.class));
    assertTrue(e.getMessage(), e.getMessage().contains("bb()"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testNotImplementingAnyWorkflowInterface() {
    POJOWorkflowImplMetadata.newInstance(NoWorkflowImpl.class);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testNotImplementingAnyNonEmptyWorkflowInterface() {
    POJOWorkflowImplMetadata.newInstance(EmptyImpl.class);
  }

  @Test
  public void testImplementingMultipleWorkflowInterfaceWithInit() {
    try {
      POJOWorkflowImplMetadata.newInstance(MultipleWorkflowWithInit.class);
      Assert.fail();
    } catch (IllegalArgumentException e) {
      assertTrue(
          e.getMessage()
              .contains(
                  "Multiple interfaces implemented while using @WorkflowInit annotation. Only one is allowed:"));
    }
  }

  @Test
  public void testWorkflowWithBadInit() {
    try {
      POJOWorkflowImplMetadata.newInstance(WorkflowWithMismatchedInit.class);
      Assert.fail();
    } catch (IllegalArgumentException e) {
      assertTrue(
          e.getMessage()
              .contains(
                  "Constructor annotated with @WorkflowInit must have the same parameters as the workflow method"));
    }
  }

  @Test
  public void testWorkflowWithPrivateInit() {
    try {
      POJOWorkflowImplMetadata.newInstance(WorkflowWithPrivateInit.class);
      Assert.fail();
    } catch (IllegalArgumentException e) {
      assertTrue(
          e.getMessage().contains("Constructor with @WorkflowInit annotation must be public"));
    }
  }

  @Test
  public void testWorkflowWithMultipleInit() {
    try {
      POJOWorkflowImplMetadata.newInstance(WorkflowWithMultipleInit.class);
      Assert.fail();
    } catch (IllegalArgumentException e) {
      assertTrue(
          e.getMessage()
              .contains(
                  "Found both a default constructor and constructor annotated with @WorkflowInit"));
    }
  }

  @Test
  public void testWorkflowInheritWithInit() {
    try {
      POJOWorkflowImplMetadata.newInstance(WorkflowInheritWithInit.class);
      Assert.fail();
    } catch (IllegalArgumentException e) {
      assertTrue(
          e.getMessage()
              .contains(
                  "No default constructor or constructor annotated with @WorkflowInit found:"));
    }
  }

  @Test
  public void testWorkflowInit() {
    POJOWorkflowImplMetadata meta = POJOWorkflowImplMetadata.newInstance(WorkflowWithInit.class);
    Assert.assertNotNull(meta.getWorkflowInit());
    Assert.assertEquals(1, meta.getWorkflowInit().getParameterCount());
    Assert.assertEquals(Integer.class, meta.getWorkflowInit().getParameterTypes()[0]);
    Assert.assertEquals(WorkflowWithInit.class, meta.getWorkflowInit().getDeclaringClass());
  }

  @Test
  public void testWorkflowWithConstructor() {
    POJOWorkflowImplMetadata meta =
        POJOWorkflowImplMetadata.newInstance(WorkflowWithConstructor.class);
    Assert.assertNull(meta.getWorkflowInit());
  }

  @Test
  public void testWorkflowWithGenericInput() {
    POJOWorkflowImplMetadata meta =
        POJOWorkflowImplMetadata.newInstance(WorkflowWithGenericInput.class);
    Assert.assertNotNull(meta.getWorkflowInit());
  }

  @Test
  public void testWorkflowWithGenericInputMismatchedInit() {
    try {
      POJOWorkflowImplMetadata.newInstance(WorkflowWithGenericInputMismatchedInit.class);
      Assert.fail();
    } catch (IllegalArgumentException e) {
      assertTrue(
          e.getMessage()
              .contains(
                  "Constructor annotated with @WorkflowInit must have the same parameters as the workflow method:"));
    }
  }

  @Test
  public void testWorkflowWithConstructorArgsNoInit() {
    try {
      POJOWorkflowImplMetadata.newInstance(WorkflowWithConstructorParameters.class);
      Assert.fail();
    } catch (IllegalArgumentException e) {
      assertTrue(
          e.getMessage()
              .contains(
                  "No default constructor or constructor annotated with @WorkflowInit found:"));
    }
    POJOWorkflowImplMetadata meta =
        POJOWorkflowImplMetadata.newInstanceForWorkflowFactory(
            WorkflowWithConstructorParameters.class);
    Assert.assertEquals(1, meta.getWorkflowMethods().size());
  }

  public static class GImpl implements POJOWorkflowInterfaceMetadataTest.G {
    @Override
    public void g() {}
  }

  public static class DImpl
      implements POJOWorkflowInterfaceMetadataTest.D, POJOWorkflowInterfaceMetadataTest.E {

    @Override
    public void a() {}

    @Override
    public String b() {
      throw new UnsupportedOperationException("unimplemented");
    }

    @Override
    public void c() {}

    @Override
    public void bb() {}

    @Override
    public void d() {}
  }

  public static class DWithAdditionalInterface extends DImpl
      implements POJOWorkflowInterfaceMetadataTest.O {
    @Override
    public void someMethod() {}
  }

  public static class DuplicatedName1Impl implements POJOWorkflowInterfaceMetadataTest.DE {

    @Override
    public void a() {}

    @Override
    public String b() {
      throw new UnsupportedOperationException("unimplemented");
    }

    @Override
    public void c() {}

    @Override
    public void bb() {}

    @Override
    public void d() {}
  }

  public static class DuplicatedName2Impl
      implements POJOWorkflowInterfaceMetadataTest.F, POJOWorkflowInterfaceMetadataTest.C {

    @Override
    public void a() {}

    @Override
    public String b() {
      throw new UnsupportedOperationException("unimplemented");
    }

    @Override
    public void c() {}

    @Override
    public void bb() {}

    @Override
    public void f() {}
  }

  public static class EmptyImpl implements POJOWorkflowInterfaceMetadataTest.Empty {
    public void foo() {}
  }

  public static class NoWorkflowImpl implements POJOWorkflowInterfaceMetadataTest.A {

    @Override
    public void a() {}
  }

  static class MultipleWorkflowWithInit
      implements POJOWorkflowInterfaceMetadataTest.F, POJOWorkflowInterfaceMetadataTest.I {
    @WorkflowInit
    public MultipleWorkflowWithInit() {}

    @Override
    public void i() {}

    @Override
    public void f() {}
  }

  static class WorkflowWithMismatchedInit implements POJOWorkflowInterfaceMetadataTest.F {
    @WorkflowInit
    public WorkflowWithMismatchedInit(Integer i) {}

    @Override
    public void f() {}
  }

  static class WorkflowWithPrivateInit implements POJOWorkflowInterfaceMetadataTest.F {
    @WorkflowInit
    private WorkflowWithPrivateInit() {}

    @Override
    public void f() {}
  }

  static class WorkflowWithMultipleInit implements POJOWorkflowInterfaceMetadataTest.H {
    @WorkflowInit
    public WorkflowWithMultipleInit(Integer i) {}

    public WorkflowWithMultipleInit() {}

    @Override
    public void h(Integer i) {}
  }

  static class WorkflowWithInit implements POJOWorkflowInterfaceMetadataTest.H {

    @WorkflowInit
    public WorkflowWithInit(Integer i) {}

    @Override
    public void h(Integer i) {}
  }

  static class WorkflowInheritWithInit extends WorkflowWithInit
      implements POJOWorkflowInterfaceMetadataTest.O {

    public WorkflowInheritWithInit(Integer i) {
      super(i);
    }

    @Override
    public void someMethod() {}
  }

  public static class WorkflowWithConstructor implements POJOWorkflowInterfaceMetadataTest.I {

    public WorkflowWithConstructor() {}

    @Override
    public void i() {}
  }

  public static class WorkflowWithConstructorParameters
      implements POJOWorkflowInterfaceMetadataTest.I {

    public WorkflowWithConstructorParameters(Integer i) {}

    @Override
    public void i() {}
  }

  public static class WorkflowWithGenericInput implements POJOWorkflowInterfaceMetadataTest.K {
    @WorkflowInit
    public WorkflowWithGenericInput(Map<String, EncodedValuesTest.Pair> input) {}

    @Override
    public void f(Map<String, EncodedValuesTest.Pair> input) {}
  }

  public static class WorkflowWithGenericInputMismatchedInit
      implements POJOWorkflowInterfaceMetadataTest.K {
    @WorkflowInit
    public WorkflowWithGenericInputMismatchedInit(Map<String, Object> input) {}

    @Override
    public void f(Map<String, EncodedValuesTest.Pair> input) {}
  }
}
