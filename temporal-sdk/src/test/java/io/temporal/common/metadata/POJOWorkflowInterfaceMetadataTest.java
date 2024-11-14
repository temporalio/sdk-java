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

import io.temporal.common.converter.EncodedValuesTest;
import io.temporal.common.metadata.testclasses.WorkflowInterfaceWithOneWorkflowMethod;
import io.temporal.worker.Worker;
import io.temporal.workflow.*;
import java.lang.reflect.Method;
import java.util.*;
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
public class POJOWorkflowInterfaceMetadataTest {
  @Test(expected = IllegalArgumentException.class)
  public void testNonInterface() {
    POJOWorkflowInterfaceMetadata.newInstance(AbstractDEImpl.class);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testEmptyInterface() {
    POJOWorkflowInterfaceMetadata.newInstance(Empty.class);
  }

  @Test
  public void testNewStubInstanceC() {
    Set<String> expected = new HashSet<>();
    expected.add("AM_C_bb");
    expected.add("a");
    expected.add("b");
    expected.add("c");

    Set<String> notExpected = new HashSet<>();
    notExpected.add("bb");

    POJOWorkflowInterfaceMetadata metadata = POJOWorkflowInterfaceMetadata.newInstance(C.class);

    metadata
        .getMethodsMetadata()
        .forEach(
            m ->
                assertTrue(
                    m.getName(),
                    expected.contains(m.getName()) && !notExpected.contains(m.getName())));
  }

  @Test
  public void testNewStubInstanceD() {
    Set<String> expected = new HashSet<>();
    expected.add("AM_C_bb");
    expected.add("a");
    expected.add("b");
    expected.add("c");
    expected.add("d");

    Set<String> notExpected = new HashSet<>();
    notExpected.add("bb");

    POJOWorkflowInterfaceMetadata metadata = POJOWorkflowInterfaceMetadata.newInstance(D.class);

    metadata
        .getMethodsMetadata()
        .forEach(
            m ->
                assertTrue(
                    m.getName(),
                    expected.contains(m.getName()) && !notExpected.contains(m.getName())));
  }

  @Test
  public void testPOJOWorkflowMethodMetadataCD() throws NoSuchMethodException {
    POJOWorkflowInterfaceMetadata cMetadata = POJOWorkflowInterfaceMetadata.newInstance(C.class);

    POJOWorkflowInterfaceMetadata dMetadata = POJOWorkflowInterfaceMetadata.newInstance(D.class);

    Method method = C.class.getDeclaredMethod("c");

    POJOWorkflowMethodMetadata cMethod = cMetadata.getMethodMetadata(method);
    POJOWorkflowMethodMetadata dMethod = dMetadata.getMethodMetadata(method);

    assertEquals(cMethod, dMethod);
    assertEquals(method, dMethod.getWorkflowMethod());
    assertEquals("c", dMethod.getName());
  }

  @Test
  public void testNewImplementationInstanceB() {
    Set<String> expected = Collections.emptySet();

    Set<String> notExpected = new HashSet<>();
    // Because this signal method doesn't belong to any WorkflowInterface, it shouldn't be included
    // in the result
    notExpected.add("a");

    POJOWorkflowInterfaceMetadata metadata =
        POJOWorkflowInterfaceMetadata.newImplementationInstance(B.class, false);

    metadata
        .getMethodsMetadata()
        .forEach(
            m ->
                assertTrue(
                    m.getName(),
                    expected.contains(m.getName()) && !notExpected.contains(m.getName())));
  }

  @Test
  public void testDynamicWorkflowInterface() {
    POJOWorkflowInterfaceMetadata metadata =
        POJOWorkflowInterfaceMetadata.newImplementationInstance(DynamicWorkflow.class, true);

    assertEquals(0, metadata.getMethodsMetadata().size());
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
              POJOWorkflowInterfaceMetadata.newInstance(interfaice);
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

  public interface O {
    void someMethod();
  }

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

  @WorkflowInterface
  public interface H {
    @WorkflowMethod
    void h(Integer i);
  }

  @WorkflowInterface
  public interface I {
    @WorkflowMethod
    void i();
  }

  @WorkflowInterface
  public interface K {
    @WorkflowMethod
    void f(Map<String, EncodedValuesTest.Pair> input);
  }

  public interface DE extends D, E {}

  @WorkflowInterface
  public interface Empty {}

  abstract static class AbstractDEImpl implements DE {}
}
