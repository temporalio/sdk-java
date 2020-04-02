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

package io.temporal.worker;

import com.google.common.base.Objects;
import io.temporal.activity.ActivityInterface;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.junit.Test;

public class ActivityInterfaceTest {

  public interface I0 {
    void baz();
  }

  public interface I1 extends I0 {
    void foo();
  }

  @ActivityInterface
  public interface I2 extends I1, I0 {
    void bar();
  }

  @ActivityInterface
  public interface I3 extends I1 {
    void foo();
  }

  @ActivityInterface
  public interface I4 extends I3 {
    void foo();

    void bar();
  }

  @ActivityInterface
  public interface I5 extends I4, I3 {}

  public interface NonActivity {
    void foobar();
  }

  public static class Impl implements I2, I4, NonActivity, I5 {

    @Override
    public void foo() {}

    @Override
    public void bar() {}

    @Override
    public void foobar() {}

    @Override
    public void baz() {}
  }

  @Test
  public void test() {
    Set<MethodInterfacePair> activityMethods =
        getAnnotatedInterfaceMethods(Impl.class, ActivityInterface.class);
    for (Iterator<MethodInterfacePair> iterator = activityMethods.iterator();
        iterator.hasNext(); ) {
      MethodInterfacePair next = iterator.next();
      System.out.println(next);
    }
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

  /** Used to override equals and hashCode of Method to ensure deduping by method name in a set. */
  static class MethodWrapper {
    private final Method method;

    MethodWrapper(Method method) {
      this.method = method;
    }

    public Method getMethod() {
      return method;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      MethodWrapper that = (MethodWrapper) o;
      return Objects.equal(method.getName(), that.method.getName());
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(method.getName());
    }
  }

  Set<MethodInterfacePair> getAnnotatedInterfaceMethods(
      Class<?> implementationClass, Class<? extends Annotation> annotationClass) {
    if (implementationClass.isInterface()) {
      throw new IllegalArgumentException(
          "Concrete class expected. Found interface: " + implementationClass.getSimpleName());
    }
    Set<MethodInterfacePair> pairs = new HashSet<>();
    // Methods inherited from interfaces that are not annotated with @ActivityInterface
    Set<MethodWrapper> ignored = new HashSet<>();
    getAnnotatedInterfaceMethods(implementationClass, annotationClass, ignored, pairs);
    return pairs;
  }

  private void getAnnotatedInterfaceMethods(
      Class<?> current,
      Class<? extends Annotation> annotationClass,
      Set<MethodWrapper> methods,
      Set<MethodInterfacePair> result) {
    // Using set to dedupe methods which are defined in both non activity parent and current
    Set<MethodWrapper> ourMethods = new HashSet<>();
    if (current.isInterface()) {
      Method[] declaredMethods = current.getDeclaredMethods();
      for (int i = 0; i < declaredMethods.length; i++) {
        Method declaredMethod = declaredMethods[i];
        ourMethods.add(new MethodWrapper(declaredMethod));
      }
    }
    Class<?>[] interfaces = current.getInterfaces();
    for (int i = 0; i < interfaces.length; i++) {
      Class<?> anInterface = interfaces[i];
      getAnnotatedInterfaceMethods(anInterface, annotationClass, ourMethods, result);
    }
    Annotation annotation = current.getAnnotation(annotationClass);
    if (annotation == null) {
      methods.addAll(ourMethods);
      return;
    }
    for (MethodWrapper method : ourMethods) {
      result.add(new MethodInterfacePair(method.getMethod(), current));
    }
  }
}
