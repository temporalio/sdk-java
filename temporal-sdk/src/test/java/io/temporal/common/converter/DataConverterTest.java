package io.temporal.common.converter;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;

public class DataConverterTest {
  // Test methods for reflection
  public String testMethodNormalParameter(String input, String names) {
    return "";
  }

  public String testMethodGenericParameter(String input, List<String> names) {
    return "";
  }

  public String testMethodGenericArrayParameter(String input, List<Integer>[] names) {
    return "";
  }

  @Test
  public void noContent() throws NoSuchMethodException {
    DataConverter dc = GlobalDataConverter.get();
    Method m = this.getClass().getMethod("testMethodGenericParameter", String.class, List.class);
    Object[] result =
        dc.fromPayloads(Optional.empty(), m.getParameterTypes(), m.getGenericParameterTypes());
    Assert.assertNull(result[0]);
    Assert.assertNull(result[1]);
  }

  @Test
  public void addParameter() throws NoSuchMethodException {
    DataConverter dc = GlobalDataConverter.get();
    Optional<Payloads> p = dc.toPayloads("test");
    Method m = this.getClass().getMethod("testMethodNormalParameter", String.class, String.class);
    Object[] result = dc.fromPayloads(p, m.getParameterTypes(), m.getGenericParameterTypes());
    Assert.assertEquals("test", result[0]);
    Assert.assertNull(result[1]);
  }

  @Test
  public void addGenericParameter() throws NoSuchMethodException {
    DataConverter dc = GlobalDataConverter.get();
    Optional<Payloads> p = dc.toPayloads("test");
    Method m = this.getClass().getMethod("testMethodGenericParameter", String.class, List.class);
    Object[] result = dc.fromPayloads(p, m.getParameterTypes(), m.getGenericParameterTypes());
    Assert.assertEquals("test", result[0]);
    Assert.assertNull(result[1]);
  }

  @Test
  public void addGenericArrayParameter() throws NoSuchMethodException {
    DataConverter dc = GlobalDataConverter.get();
    Optional<Payloads> p = dc.toPayloads("test");
    Method m =
        this.getClass().getMethod("testMethodGenericArrayParameter", String.class, List[].class);
    Object[] result = dc.fromPayloads(p, m.getParameterTypes(), m.getGenericParameterTypes());
    Assert.assertEquals("test", result[0]);
    Assert.assertNull(result[1]);
  }

  @Test
  public void passesSerializationTypeHintToPayloadConverter() throws NoSuchMethodException {
    Method method =
        this.getClass().getMethod("testMethodGenericParameter", String.class, List.class);
    Type expectedType = method.getGenericParameterTypes()[1];
    Type[] receivedType = new Type[1];
    Payload expectedPayload = Payload.getDefaultInstance();
    PayloadConverter payloadConverter =
        new PayloadConverter() {
          @Override
          public String getEncodingType() {
            return "test/type-hint";
          }

          @Override
          public Optional<Payload> toData(Object value) {
            throw new AssertionError("The type-aware overload should be used");
          }

          @Override
          public Optional<Payload> toData(Object value, Type valueType) {
            receivedType[0] = valueType;
            return Optional.of(expectedPayload);
          }

          @Override
          public <T> T fromData(Payload content, Class<T> valueType, Type valueGenericType) {
            throw new UnsupportedOperationException();
          }
        };

    Optional<Payload> result =
        new DefaultDataConverter(payloadConverter)
            .toPayload(java.util.Collections.emptyList(), expectedType);

    Assert.assertSame(expectedPayload, result.get());
    Assert.assertSame(expectedType, receivedType[0]);
  }

  @Test
  public void passesSerializationTypeHintsToPayloadConverter() throws NoSuchMethodException {
    Method method =
        this.getClass().getMethod("testMethodGenericParameter", String.class, List.class);
    Type[] expectedTypes = method.getGenericParameterTypes();
    List<Type> receivedTypes = new java.util.ArrayList<>();
    PayloadConverter payloadConverter =
        new PayloadConverter() {
          @Override
          public String getEncodingType() {
            return "test/type-hints";
          }

          @Override
          public Optional<Payload> toData(Object value) {
            throw new AssertionError("The type-aware overload should be used");
          }

          @Override
          public Optional<Payload> toData(Object value, Type valueType) {
            receivedTypes.add(valueType);
            return Optional.of(Payload.getDefaultInstance());
          }

          @Override
          public <T> T fromData(Payload content, Class<T> valueType, Type valueGenericType) {
            throw new UnsupportedOperationException();
          }
        };

    new DefaultDataConverter(payloadConverter)
        .toPayloads(new Object[] {"value", java.util.Collections.emptyList()}, expectedTypes);

    Assert.assertArrayEquals(expectedTypes, receivedTypes.toArray(new Type[0]));
  }

  @Test
  public void typeHintFallsBackToLegacyPayloadConverter() {
    Payload expectedPayload = Payload.getDefaultInstance();
    PayloadConverter payloadConverter =
        new PayloadConverter() {
          @Override
          public String getEncodingType() {
            return "test/legacy";
          }

          @Override
          public Optional<Payload> toData(Object value) {
            return Optional.of(expectedPayload);
          }

          @Override
          public <T> T fromData(Payload content, Class<T> valueType, Type valueGenericType) {
            throw new UnsupportedOperationException();
          }
        };

    Optional<Payload> result =
        new DefaultDataConverter(payloadConverter).toPayload("value", String.class);

    Assert.assertSame(expectedPayload, result.get());
  }

  @Test
  public void typeHintFallsBackToLegacyDataConverter() {
    Payload expectedPayload = Payload.getDefaultInstance();
    DataConverter dataConverter =
        new DataConverter() {
          @Override
          public <T> Optional<Payload> toPayload(T value) {
            return Optional.of(expectedPayload);
          }

          @Override
          public <T> T fromPayload(Payload payload, Class<T> valueClass, Type valueType) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Optional<Payloads> toPayloads(Object... values) {
            throw new UnsupportedOperationException();
          }

          @Override
          public <T> T fromPayloads(
              int index, Optional<Payloads> content, Class<T> valueType, Type valueGenericType) {
            throw new UnsupportedOperationException();
          }
        };

    Optional<Payload> result = dataConverter.toPayload("value", String.class);

    Assert.assertSame(expectedPayload, result.get());
  }

  @Test
  public void typeHintsFallBackToLegacyDataConverter() {
    Payloads expectedPayloads = Payloads.getDefaultInstance();
    DataConverter dataConverter =
        new DataConverter() {
          @Override
          public <T> Optional<Payload> toPayload(T value) {
            throw new UnsupportedOperationException();
          }

          @Override
          public <T> T fromPayload(Payload payload, Class<T> valueClass, Type valueType) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Optional<Payloads> toPayloads(Object... values) {
            return Optional.of(expectedPayloads);
          }

          @Override
          public <T> T fromPayloads(
              int index, Optional<Payloads> content, Class<T> valueType, Type valueGenericType) {
            throw new UnsupportedOperationException();
          }
        };

    Optional<Payloads> result =
        dataConverter.toPayloads(new Object[] {"value"}, new Type[] {String.class});

    Assert.assertSame(expectedPayloads, result.get());
  }
}
