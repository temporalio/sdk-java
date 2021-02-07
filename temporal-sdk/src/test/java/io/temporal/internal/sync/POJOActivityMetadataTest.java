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
import static org.junit.Assert.fail;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.common.metadata.POJOActivityImplMetadata;
import io.temporal.common.metadata.POJOActivityInterfaceMetadata;
import io.temporal.common.metadata.POJOActivityMethodMetadata;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class POJOActivityMetadataTest {

  public interface A {
    void a();
  }

  public interface B extends A {
    void b();

    void bb();
  }

  @ActivityInterface(namePrefix = "C_")
  public interface C extends B, A {
    void c();

    @ActivityMethod(name = "AM_C_bb")
    void bb();
  }

  @ActivityInterface
  public interface E extends B {
    @ActivityMethod(name = "AM_E_bb")
    void bb();
  }

  @ActivityInterface
  public interface D extends C {
    void d();
  }

  @ActivityInterface
  public interface F {
    @ActivityMethod(name = "AM_C_bb")
    void f();
  }

  public interface DE extends D, E {}

  @ActivityInterface
  interface G {
    @ActivityMethod(name = "AM_G_bb")
    void g();
  }

  static class DImpl implements D, E {

    @Override
    public void a() {}

    @Override
    public void b() {}

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
    public void b() {}

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
    public void b() {}

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

  @ActivityInterface
  public interface Empty {}

  class EmptyImpl implements Empty {
    public void foo() {}
  }

  class NoActivityImpl implements A {

    @Override
    public void a() {}
  }

  @Test(expected = IllegalArgumentException.class)
  public void testActivityRegistration() {
    POJOActivityImplMetadata.newInstance(EmptyImpl.class);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testNoActivityInterfaceRegistration() {
    POJOActivityImplMetadata.newInstance(NoActivityImpl.class);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testDuplicatedActivityTypeRegistration() {
    POJOActivityImplMetadata.newInstance(DuplicatedNameImpl.class);
  }

  @Test
  public void testActivityImplementationRegistration() {
    Set<String> expected = new HashSet<>();
    expected.add("AM_C_bb");
    expected.add("AM_E_bb");
    expected.add("C_A");
    expected.add("C_B");
    expected.add("C_C");
    expected.add("D");
    expected.add("A");
    expected.add("B");

    POJOActivityImplMetadata activityImplMetadata =
        POJOActivityImplMetadata.newInstance(DImpl.class);
    List<POJOActivityMethodMetadata> activityMethods = new ArrayList<>();
    for (POJOActivityInterfaceMetadata activityInterface :
        activityImplMetadata.getActivityInterfaces()) {
      activityMethods.addAll(activityInterface.getMethodsMetadata());
    }
    Set<String> activityTypes = new HashSet<>();
    for (POJOActivityMethodMetadata activityMethod : activityMethods) {
      activityTypes.add(activityMethod.getActivityTypeName());
    }
    assertEquals(expected, activityTypes);
  }

  @Test
  public void testDuplicatedActivityImplementationRegistration() {
    try {
      POJOActivityImplMetadata.newInstance(DEImpl.class);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("bb()"));
    }
  }

  @Test
  public void testNonPublicInterface() {
    try {
      POJOActivityImplMetadata.newInstance(GImpl.class);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertTrue(
          e.getMessage(),
          e.getMessage().contains("Interface with @ActivityInterface annotation must be public"));
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void testNonInterface() {
    POJOActivityInterfaceMetadata.newInstance(DImpl.class);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testEmptyInterface() {
    POJOActivityInterfaceMetadata.newInstance(Empty.class);
  }

  @Test
  public void testActivityInterface() throws NoSuchMethodException {
    Set<String> expected = new HashSet<>();
    expected.add("AM_C_bb");
    expected.add("AM_E_bb");
    expected.add("C_a");
    expected.add("C_b");
    expected.add("C_c");
    expected.add("d");
    expected.add("a");
    expected.add("b");

    POJOActivityInterfaceMetadata dMetadata = POJOActivityInterfaceMetadata.newInstance(D.class);
    Method c = C.class.getDeclaredMethod("c");
    POJOActivityMethodMetadata cMethod = dMetadata.getMethodMetadata(c);
    assertEquals(c, cMethod.getMethod());
    assertEquals("C_C", cMethod.getActivityTypeName());
  }
}
