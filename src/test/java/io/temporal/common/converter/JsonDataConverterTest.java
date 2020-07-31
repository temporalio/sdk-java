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

import com.google.common.base.Objects;
import io.temporal.api.common.v1.Payloads;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.Test;

public class JsonDataConverterTest {

  private final DataConverter converter = DataConverter.getDefaultInstance();

  public static void foo(List<UUID> arg) {}

  @Test
  public void testUUIDList() throws NoSuchMethodException {
    Method m = JsonDataConverterTest.class.getDeclaredMethod("foo", List.class);
    Class<?> parameterType = m.getParameterTypes()[0];
    Type genericParameterType = m.getGenericParameterTypes()[0];

    List<UUID> list = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      list.add(UUID.randomUUID());
    }

    Optional<Payloads> data = converter.toPayloads(list);
    @SuppressWarnings("unchecked")
    List<UUID> result =
        (List<UUID>) converter.fromPayloads(0, data, parameterType, genericParameterType);
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

    Struct1 struct1 = new Struct1(123, "Bar");
    List<Struct1> list = new ArrayList<>();
    list.add(new Struct1(234, "s1"));
    list.add(new Struct1(567, "s2"));
    Optional<Payloads> data =
        converter.toPayloads(1234, struct1, "a string", list, "an extra string :o!!!");
    Object[] deserializedArguments =
        DataConverter.arrayFromPayloads(
            converter, data, m.getParameterTypes(), m.getGenericParameterTypes());
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
    Optional<Payloads> data = converter.toPayloads(1);
    @SuppressWarnings("unchecked")
    Object[] deserializedArguments =
        DataConverter.arrayFromPayloads(
            converter, data, m.getParameterTypes(), m.getGenericParameterTypes());
    assertEquals(5, deserializedArguments.length);
    assertEquals(1, (int) deserializedArguments[0]);
    assertEquals(null, deserializedArguments[1]);
    assertEquals(null, deserializedArguments[2]);
    assertEquals(null, deserializedArguments[3]);
    assertEquals(null, deserializedArguments[4]);
  }
}
