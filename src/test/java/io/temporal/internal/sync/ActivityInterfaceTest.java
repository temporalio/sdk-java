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

import com.google.common.base.Objects;
import io.temporal.activity.ActivityInterface;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public class ActivityInterfaceTest {

  interface A {
    void a();
  }

  interface B extends A {
    void b();
  }

  @ActivityInterface
  interface C extends B, A {
    void c();
  }

  @ActivityInterface
  interface D extends B {
    void b();
  }

  @ActivityInterface
  interface E extends D {
    void b();

    void c();
  }

  @ActivityInterface
  interface F extends E, D {}

  interface NonActivityA {
    void nonActivityA();
  }

  interface NonActivityB extends NonActivityA {
    void nonActivityB();
  }

  static class Impl implements C, E, NonActivityB, F {

    @Override
    public void a() {}

    @Override
    public void b() {}

    @Override
    public void c() {}

    @Override
    public void nonActivityA() {}

    @Override
    public void nonActivityB() {}
  }

  @ActivityInterface
  interface Empty {}

  class EmptyImpl implements Empty {
    public void foo() {}
  }

  class NoActivityImpl implements A {

    @Override
    public void a() {}
  }

  POJOActivityTaskHandler handler;

  @Before
  public void setUp() throws Exception {
    handler = new POJOActivityTaskHandler(null, "default", null, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testActivityRegistration() {
    handler.setActivitiesImplementation(new Object[] {new EmptyImpl()});
  }

  @Test(expected = IllegalArgumentException.class)
  public void testNoActivityInterfaceRegistration() {
    handler.setActivitiesImplementation(new Object[] {new NoActivityImpl()});
  }

  @Test
  public void testEmptyActivityRegistration() {
    handler.setActivitiesImplementation(new Object[] {new Impl()});
    Set<String> types = handler.getRegisteredActivityTypes();
    System.out.println(types);
    Set<String> expected = new HashSet<>();
    expected.add("C_a");
    expected.add("C_b");
    expected.add("C_c");
    expected.add("D_a");
    expected.add("D_b");
    expected.add("E_b");
    expected.add("E_c");
    assertEquals(expected, types);
  }

  static class MethodInterfacePair {
    private final Method method;
    private final Class<?> type;

    MethodInterfacePair(Method method, Class<?> type) {
      this.method = method;
      this.type = type;
    }

    public Method getMethod() {
      return method;
    }

    public Class<?> getType() {
      return type;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      MethodInterfacePair that = (MethodInterfacePair) o;
      return Objects.equal(method, that.method) && Objects.equal(type, that.type);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(method, type);
    }

    @Override
    public String toString() {
      return "MethodInterfacePair{" + "method=" + method + ", type=" + type + '}';
    }
  }
}
