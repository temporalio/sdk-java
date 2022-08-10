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

import io.temporal.common.metadata.testclasses.WorkflowInterfaceWithOneWorkflowMethod;
import io.temporal.worker.Worker;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.modifier.ModifierContributor;
import net.bytebuddy.description.modifier.Ownership;
import net.bytebuddy.description.modifier.SyntheticState;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.FixedValue;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class POJOWorkflowMetadataTest {

  public interface A {
    @SignalMethod
    void a();
  }

  public interface B extends A {
    @QueryMethod
    String b();

    void bb();
  }

  @WorkflowInterface
  public interface C extends B, A {
    @SignalMethod
    void c();

    @WorkflowMethod(name = "AM_C_bb")
    void bb();
  }

  @WorkflowInterface
  public interface E extends B {
    @WorkflowMethod(name = "AM_E_bb")
    void bb();
  }

  @WorkflowInterface
  public interface D extends C {
    @SignalMethod
    void d();
  }

  @WorkflowInterface
  public interface F {
    @WorkflowMethod(name = "AM_C_bb")
    void f();
  }

  @WorkflowInterface
  interface G {
    @WorkflowMethod
    void g();
  }

  public interface DE extends D, E {}

  static class DImpl implements D, E {

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

  static class DEImpl implements DE {

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

  static class DuplicatedNameImpl implements F, C {

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

  static class GImpl implements G {
    @Override
    public void g() {}
  }

  @WorkflowInterface
  public interface Empty {}

  class EmptyImpl implements Empty {
    public void foo() {}
  }

  class NoWorkflowImpl implements A {

    @Override
    public void a() {}
  }

  @Test(expected = IllegalArgumentException.class)
  public void testWorkflowRegistration() {
    POJOWorkflowImplMetadata.newInstance(EmptyImpl.class);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testNoWorkflowInterfaceRegistration() {
    POJOWorkflowImplMetadata.newInstance(NoWorkflowImpl.class);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testDuplicatedWorkflowTypeRegistration() {
    POJOWorkflowImplMetadata.newInstance(DuplicatedNameImpl.class);
  }

  @Test
  public void testWorkflowImplementationRegistration() {
    Set<String> wExpected = new HashSet<>();
    wExpected.add("AM_C_bb");
    wExpected.add("AM_E_bb");

    Set<String> qExpected = new HashSet<>();
    qExpected.add("b");

    Set<String> sExpected = new HashSet<>();
    sExpected.add("a");
    sExpected.add("c");
    sExpected.add("d");

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

  @Test
  public void testDuplicatedWorkflowImplementationRegistration() {
    try {
      POJOWorkflowImplMetadata.newInstance(DEImpl.class);
      fail("expected an illegal argument exception");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("bb()"));
    }
  }

  @Test
  public void testNonPublicInterface() {
    try {
      POJOWorkflowImplMetadata.newInstance(GImpl.class);
      fail("expected an illegal argument exception");
    } catch (IllegalArgumentException e) {
      assertTrue(
          e.getMessage(),
          e.getMessage().contains("Interface with @WorkflowInterface annotation must be public"));
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void testNonInterface() {
    POJOWorkflowInterfaceMetadata.newStubInstance(DImpl.class);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testEmptyInterface() {
    POJOWorkflowInterfaceMetadata.newStubInstance(Empty.class);
  }

  @Test
  public void testWorkflowInterface() throws NoSuchMethodException {
    Set<String> expected = new HashSet<>();
    expected.add("AM_C_bb");
    expected.add("AM_E_bb");
    expected.add("C_a");
    expected.add("C_b");
    expected.add("C_c");
    expected.add("D_d");
    expected.add("E_a");
    expected.add("E_b");

    POJOWorkflowInterfaceMetadata dMetadata =
        POJOWorkflowInterfaceMetadata.newStubInstance(D.class);
    Method c = C.class.getDeclaredMethod("c");
    POJOWorkflowMethodMetadata cMethod = dMetadata.getMethodMetadata(c);
    assertEquals(c, cMethod.getWorkflowMethod());
    assertEquals("c", cMethod.getName());
  }

  @Test
  public void testGetWorkflowType() {
    assertEquals("AM_C_bb", Worker.getWorkflowType(F.class));
  }

  @Test
  @Parameters({
    "false, true, false, false, false",
    "true, false, false, false, false",
    "false, true, true, false, true",
    "true, false, true, true, false"
  })
  public void testSyntheticAndStaticMethods(
      boolean synthetic,
      boolean statik,
      boolean annotated,
      boolean shouldBeConsideredAWorkflowMethod,
      boolean shouldThrow)
      throws Throwable {
    Class<?> interfaice = generateWorkflowInterfaceWithQueryMethod(synthetic, statik, annotated);

    ThrowingRunnable r =
        () -> {
          POJOWorkflowInterfaceMetadata metadata =
              POJOWorkflowInterfaceMetadata.newStubInstance(interfaice);
          assertEquals(
              shouldBeConsideredAWorkflowMethod,
              metadata.getMethodsMetadata().stream()
                  .anyMatch(m -> m.getWorkflowMethod().getName().equals("method")));
        };
    if (shouldThrow) {
      assertThrows(IllegalArgumentException.class, r);
    } else {
      r.run();
    }
  }

  private Class<?> generateWorkflowInterfaceWithQueryMethod(
      boolean synthetic, boolean statik, boolean annotated) {
    DynamicType.Builder<?> builder =
        new ByteBuddy()
            .makeInterface(WorkflowInterfaceWithOneWorkflowMethod.class)
            .name("GeneratedWorkflowInterface")
            .annotateType(AnnotationDescription.Builder.ofType(WorkflowInterface.class).build());
    Collection<ModifierContributor.ForMethod> modifiers = new ArrayList<>();
    modifiers.add(Visibility.PUBLIC);
    if (synthetic) {
      modifiers.add(SyntheticState.SYNTHETIC);
    }
    if (statik) {
      modifiers.add(Ownership.STATIC);
    }

    DynamicType.Builder.MethodDefinition.ParameterDefinition.Initial<?> methodInitial =
        builder.defineMethod("method", String.class, modifiers);
    DynamicType.Builder.MethodDefinition<?> methodDefinition =
        statik ? methodInitial.intercept(FixedValue.value("hi")) : methodInitial.withoutCode();

    if (annotated) {
      methodDefinition =
          methodDefinition.annotateMethod(
              AnnotationDescription.Builder.ofType(QueryMethod.class).build());
    }

    return methodDefinition.make().load(this.getClass().getClassLoader()).getLoaded();
  }
}
