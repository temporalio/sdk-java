/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.worker.Worker;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class POJOWorkflowMetadataTest {

  interface A {
    @SignalMethod
    void a();
  }

  interface B extends A {
    @QueryMethod
    String b();

    void bb();
  }

  @WorkflowInterface
  interface C extends B, A {
    @SignalMethod
    void c();

    @WorkflowMethod(name = "AM_C_bb")
    void bb();
  }

  @WorkflowInterface
  interface E extends B {
    @WorkflowMethod(name = "AM_E_bb")
    void bb();
  }

  @WorkflowInterface
  interface D extends C {
    @SignalMethod
    void d();
  }

  @WorkflowInterface
  interface F {
    @WorkflowMethod(name = "AM_C_bb")
    void f();
  }

  interface DE extends D, E {}

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

  @WorkflowInterface
  interface Empty {}

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

    POJOWorkflowImplMetadata dMetadata = POJOWorkflowImplMetadata.newInstance(DImpl.class);
    Set<String> wTypes = dMetadata.getWorkflowTypes();
    Set<String> qTypes = dMetadata.getQueryTypes();
    Set<String> sTypes = dMetadata.getSignalTypes();
    assertEquals(wExpected, wTypes);
    assertEquals(sExpected, sTypes);
    assertEquals(qExpected, qTypes);
  }

  @Test
  public void testDuplicatedWorkflowImplementationRegistration() {
    try {
      POJOWorkflowImplMetadata.newInstance(DEImpl.class);
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("bb()"));
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void testNonInterface() {
    POJOWorkflowInterfaceMetadata.newInstance(DImpl.class);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testEmptyInterface() {
    POJOWorkflowInterfaceMetadata.newInstance(Empty.class);
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

    POJOWorkflowInterfaceMetadata dMetadata = POJOWorkflowInterfaceMetadata.newInstance(D.class);
    Method c = C.class.getDeclaredMethod("c");
    POJOWorkflowMethodMetadata cMethod = dMetadata.getMethodMetadata(c);
    assertEquals(c, cMethod.getWorkflowMethod());
    assertEquals("c", cMethod.getName());
  }

  @Test
  public void testGetWorkflowType() {
    assertEquals("AM_C_bb", Worker.getWorkflowType(F.class));
  }
}
