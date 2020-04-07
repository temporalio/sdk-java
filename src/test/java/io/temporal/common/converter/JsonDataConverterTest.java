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

package io.temporal.common.converter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.google.common.base.Objects;
import io.temporal.activity.Activity;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public class JsonDataConverterTest {

  private final DataConverter converter = GsonJsonDataConverter.getInstance();

  public static void foo(List<UUID> arg) {}

  @Test
  public void testUUIDList() throws NoSuchMethodException {
    Method m = JsonDataConverterTest.class.getDeclaredMethod("foo", List.class);
    Type arg = m.getGenericParameterTypes()[0];

    List<UUID> list = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      list.add(UUID.randomUUID());
    }

    byte[] data = converter.toData(list);
    @SuppressWarnings("unchecked")
    List<UUID> result = (List<UUID>) converter.fromDataArray(data, arg)[0];
    assertEquals(result.toString(), list, result);
  }

  public static class Struct1 {
    private int foo;
    private String bar;

    public Struct1() {}

    public Struct1(int foo, String bar) {
      this.foo = foo;
      this.bar = bar;
    }

    public int getFoo() {
      return foo;
    }

    public String getBar() {
      return bar;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Struct1 struct1 = (Struct1) o;
      return foo == struct1.foo && Objects.equal(bar, struct1.bar);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(foo, bar);
    }
  }

  public static void fourArguments(int one, Struct1 two, String three, List<Struct1> four) {}

  public static void aLotOfArguments(
      int one, Struct1 two, String three, Object obj, int[] intArr) {}

  @Test
  public void AdditionalInputArgumentsAreIgnored() throws NoSuchMethodException {
    Method m =
        JsonDataConverterTest.class.getDeclaredMethod(
            "fourArguments", int.class, Struct1.class, String.class, List.class);
    Type[] arg = m.getGenericParameterTypes();

    Struct1 struct1 = new Struct1(123, "Bar");
    List<Struct1> list = new ArrayList<>();
    list.add(new Struct1(234, "s1"));
    list.add(new Struct1(567, "s2"));
    byte[] data = converter.toData(1234, struct1, "a string", list, "an extra string :o!!!");
    Object[] deserializedArguments = converter.fromDataArray(data, arg);
    assertEquals(4, deserializedArguments.length);
    assertEquals(1234, (int) deserializedArguments[0]);
    assertEquals(struct1, deserializedArguments[1]);
    assertEquals("a string", deserializedArguments[2]);
    @SuppressWarnings("unchecked")
    List<Struct1> deserializedList = (List<Struct1>) deserializedArguments[3];
    assertEquals(list, deserializedList);
  }

  @Test
  public void MissingInputArgumentsArePopulatedWithDefaultValues() throws NoSuchMethodException {
    Method m =
        JsonDataConverterTest.class.getDeclaredMethod(
            "aLotOfArguments", int.class, Struct1.class, String.class, Object.class, int[].class);
    Type[] arg = m.getGenericParameterTypes();

    byte[] data = converter.toData(1);
    @SuppressWarnings("unchecked")
    Object[] deserializedArguments = converter.fromDataArray(data, arg);
    assertEquals(5, deserializedArguments.length);
    assertEquals(1, (int) deserializedArguments[0]);
    assertEquals(null, deserializedArguments[1]);
    assertEquals(null, deserializedArguments[2]);
    assertEquals(null, deserializedArguments[3]);
    assertEquals(null, deserializedArguments[4]);
  }

  @Test
  public void testClass() {

    byte[] data = converter.toData(this.getClass());
    @SuppressWarnings("unchecked")
    Class result = converter.fromData(data, Class.class, Class.class);
    assertEquals(result.toString(), this.getClass(), result);
  }

  public static class NonSerializableException extends RuntimeException {
    @SuppressWarnings("unused")
    private final InputStream file; // gson chokes on this field

    private final String foo;

    public NonSerializableException(Throwable cause) {
      super(cause);
      try {
        file = new FileInputStream(File.createTempFile("foo", "bar"));
      } catch (IOException e) {
        throw Activity.wrap(e);
      }
      foo = "bar";
    }
  }

  @Test
  public void testException() {
    RuntimeException rootException = new IllegalArgumentException("root exception");
    NonSerializableException nonSerializableCause = new NonSerializableException(rootException);
    RuntimeException e = new RuntimeException("application exception", nonSerializableCause);

    byte[] converted = converter.toData(e);
    String serialized = new String(converted);
    fail("SEERIALIZED: " + serialized);
    //    RuntimeException fromConverted =
    //        converter.fromData(converted, RuntimeException.class, RuntimeException.class);
    //    fromConverted.printStackTrace();
    //    assertEquals(RuntimeException.class, fromConverted.getClass());
    //    assertEquals("application exception", fromConverted.getMessage());
    //
    //    Throwable causeFromConverted = fromConverted.getCause();
    //    assertNotNull(causeFromConverted);
    //    assertEquals(DataConverterException.class, causeFromConverted.getClass());
    //    assertNotNull(causeFromConverted.getCause());
    //    assertEquals(StackOverflowError.class, causeFromConverted.getCause().getClass());
    //
    //    assertNotNull(causeFromConverted.getSuppressed());
    //    assertEquals(1, causeFromConverted.getSuppressed().length);
    //
    //    assertEquals("root exception", causeFromConverted.getSuppressed()[0].getMessage());
  }
}
