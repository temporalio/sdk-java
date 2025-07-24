package io.temporal.common.converter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.temporal.api.common.v1.Payloads;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class DataConverterGenericTypeTest {

  @Test
  public void testFromPayloadsWithGenericListParameter() throws Exception {
    DataConverter dataConverter = DefaultDataConverter.newDefaultInstance();
    Method method = TestInterface.class.getMethod("processData", String.class, List.class);
    Optional<Payloads> payloads = dataConverter.toPayloads("test-data");

    Object[] args =
        dataConverter.fromPayloads(
            payloads, method.getParameterTypes(), method.getGenericParameterTypes());

    assertEquals(2, args.length);
    assertEquals("test-data", args[0]);
    assertNull(args[1]);
  }

  @Test
  public void testFromPayloadsWithEmptyPayloads() throws Exception {
    DataConverter dataConverter = DefaultDataConverter.newDefaultInstance();
    Method method = TestInterface.class.getMethod("processData", String.class, List.class);
    Object[] args =
        dataConverter.fromPayloads(
            Optional.empty(), method.getParameterTypes(), method.getGenericParameterTypes());

    assertEquals(2, args.length);
    assertNull(args[0]);
    assertNull(args[1]);
  }

  @Test
  public void testGetRawClassWithGenericArrayType() throws Exception {
    Method method = TestInterface.class.getMethod("processGenericArray", Object[].class);
    Type[] genericParameterTypes = method.getGenericParameterTypes();
    Type genericArrayType = genericParameterTypes[0];

    assertTrue("Expected GenericArrayType instance", genericArrayType instanceof GenericArrayType);

    Class<?> rawClass = DataConverter.getRawClass(genericArrayType);
    assertEquals(Object[].class, rawClass);
  }

  @Test
  public void testGetRawClassWithParameterizedType() throws Exception {
    Method method = TestInterface.class.getMethod("processData", String.class, List.class);
    Type[] genericParameterTypes = method.getGenericParameterTypes();
    Type parameterizedType = genericParameterTypes[1];

    assertTrue(
        "Expected ParameterizedType instance", parameterizedType instanceof ParameterizedType);

    Class<?> rawClass = DataConverter.getRawClass(parameterizedType);
    assertEquals(List.class, rawClass);
  }

  @Test
  public void testGetRawClassWithRegularClass() {
    Class<?> rawClass = DataConverter.getRawClass(String.class);
    assertEquals(String.class, rawClass);
  }

  @Test
  public void testGetRawClassWithUnsupportedType() {
    Type unsupportedType = new Type() {};
    Class<?> rawClass = DataConverter.getRawClass(unsupportedType);
    assertEquals(Object.class, rawClass);
  }

  public interface TestInterface {
    void processData(String data, List<String> items);

    <T> void processGenericArray(T[] items);
  }
}
